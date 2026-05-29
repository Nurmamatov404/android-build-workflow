"""
MLBB AI Trainer — YouTube video asosida model o'qitish

Pipeline:
  1. YouTube videolarni yuklab olish (yt-dlp)
  2. Kadrlarni ajratib olish
  3. O'yin elementlarini aniqlash (ScreenAnalyzer logic)
  4. Pseudo-label yaratish (frame farqlari orqali)
  5. CNN+LSTM model o'qitish
  6. .tflite ga eksport qilish

Foydalanish:
  python train.py --urls "URL1,URL2,URL3" --output model.tflite
"""

import argparse
import os
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

import cv2
import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers, Model
from tqdm import tqdm

# ─── Konstantalar ───────────────────────────────────────────────────────────
SEQ_LEN = 4          # nechta ketma-ket kadr
IMG_SIZE = 224       # kadr o'lchami
FPS = 10             # kadr ajratish chastotasi
MIN_JOYSTICK_AREA = 2000
MIN_SKILL_AREA = 400

# ─── Video yuklab olish ─────────────────────────────────────────────────────

def download_videos(urls: list[str], out_dir: str) -> list[str]:
    paths = []
    for url in urls:
        url = url.strip()
        if not url:
            continue
        print(f"[INFO] Yuklab olinmoqda: {url}")
        subprocess.run([
            "yt-dlp", "-f", "mp4", "-o", f"{out_dir}/%(id)s.%(ext)s",
            "--quiet", "--no-warnings", url
        ], check=True)
        result = subprocess.run(
            ["yt-dlp", "--get-id", "--quiet", url],
            capture_output=True, text=True
        )
        video_id = result.stdout.strip()
        mp4 = os.path.join(out_dir, f"{video_id}.mp4")
        webm = os.path.join(out_dir, f"{video_id}.webm")
        if os.path.exists(mp4):
            paths.append(mp4)
        elif os.path.exists(webm):
            paths.append(webm)
    return paths

# ─── Kadrlarni ajratish ────────────────────────────────────────────────────

def extract_frames(video_path: str, out_dir: str, fps: int = FPS) -> list[str]:
    os.makedirs(out_dir, exist_ok=True)
    cap = cv2.VideoCapture(video_path)
    original_fps = cap.get(cv2.CAP_PROP_FPS)
    if original_fps <= 0:
        original_fps = 30
    frame_interval = max(1, int(original_fps / fps))

    frames = []
    count = 0
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        if count % frame_interval == 0:
            fname = os.path.join(out_dir, f"frame_{len(frames):06d}.jpg")
            cv2.imwrite(fname, frame)
            frames.append(fname)
        count += 1
    cap.release()
    print(f"[INFO] {len(frames)} ta kadr ajratildi: {video_path}")
    return frames

# ─── Game elementlarini aniqlash (ScreenAnalyzer port) ──────────────────────

def detect_joystick(frame: np.ndarray) -> tuple | None:
    h, w = frame.shape[:2]
    bottom_quarter = h * 2 // 3
    left_quarter = w // 2
    best_x, best_y, best_score = -1, -1, 0.0
    step = 8

    for y in range(bottom_quarter, h, step):
        for x in range(0, left_quarter, step):
            b, g, r = frame[y, x].astype(float)
            brightness = (r + g + b) / 3.0
            is_dark = brightness < 80
            has_green = g > r * 1.1 and g > b * 1.1 and brightness > 30

            if is_dark or has_green:
                area = count_similar(frame, x, y, 30)
                score = 1.0 + area
                if score > best_score:
                    best_score = score
                    best_x, best_y = x, y

    if best_score > MIN_JOYSTICK_AREA / (step * step):
        return (best_x, best_y)
    return None

def detect_skill_buttons(frame: np.ndarray, joystick: tuple | None) -> list[dict]:
    h, w = frame.shape[:2]
    bottom_half = h // 2
    right_third = w * 2 // 3
    buttons = []
    step = 6

    for y in range(bottom_half, h, step):
        for x in range(right_third, w, step):
            b, g, r = frame[y, x].astype(float)
            brightness = (r + g + b) / 3.0

            if 30.0 <= brightness <= 120.0:
                area = count_similar(frame, x, y, 20)
                if area > MIN_SKILL_AREA:
                    dup = any(np.hypot(b["x"] - x, b["y"] - y) < 50 for b in buttons)
                    if not dup:
                        conf = min(area / 5000.0, 1.0)
                        buttons.append({"x": x, "y": y, "radius": 25, "confidence": conf})

    buttons.sort(key=lambda b: b["y"], reverse=True)
    labeled = []
    for i, btn in enumerate(buttons):
        label = "ultimate" if (i == len(buttons) - 1 and btn["y"] < h * 0.7) else \
                "skill3" if i == 0 else "skill2" if i == 1 else "skill1"
        labeled.append({**btn, "label": label})
    labeled.sort(key=lambda b: b["x"])
    return labeled

def detect_attack(frame: np.ndarray, skills: list[dict]) -> tuple | None:
    h, w = frame.shape[:2]
    bottom_area = h * 4 // 5
    right_edge = w * 4 // 5
    best_x, best_y, best_score = -1, -1, 0.0
    step = 6

    for y in range(bottom_area, h, step):
        for x in range(right_edge, w, step):
            is_skill = any(np.hypot(s["x"] - x, s["y"] - y) < 60 for s in skills)
            if is_skill:
                continue
            b, g, r = frame[y, x].astype(float)
            brightness = (r + g + b) / 3.0
            if brightness > 100:
                area = count_similar(frame, x, y, 30)
                if area > MIN_SKILL_AREA / 2 and area > best_score:
                    best_score = area
                    best_x, best_y = x, y

    return (best_x, best_y) if best_score > 0 else None

def count_similar(frame: np.ndarray, cx: int, cy: int, threshold: int, radius: int = 40) -> int:
    h, w = frame.shape[:2]
    center = frame[cy, cx].astype(float)
    count = 0
    for dy in range(-radius, radius + 1, 2):
        for dx in range(-radius, radius + 1, 2):
            x, y = cx + dx, cy + dy
            if 0 <= x < w and 0 <= y < h:
                p = frame[y, x].astype(float)
                diff = abs(p[2] - center[2]) + abs(p[1] - center[1]) + abs(p[0] - center[0])
                if diff < threshold:
                    count += 1
    return count

def analyze_frame(frame: np.ndarray) -> dict:
    """Kadrdan o'yin elementlarini aniqlash"""
    result = {
        "joystick": detect_joystick(frame),
        "skills": detect_skill_buttons(frame, None),
        "attack": detect_attack(frame, []),
    }
    return result

# ─── Pseudo-label yaratish ──────────────────────────────────────────────────

def create_pseudo_labels(frames: list[np.ndarray]) -> list[dict]:
    """
    Ketma-ket kadrlardan pseudo-label yaratish.
    Frame farqlari orqali action turlarini aniqlaymiz.
    """
    labels = []
    for i in range(len(frames) - 1):
        curr = frames[i]
        nxt = frames[i + 1]
        h, w = curr.shape[:2]

        # Frame farqini hisoblash
        diff = cv2.absdiff(curr, nxt)
        gray_diff = cv2.cvtColor(diff, cv2.COLOR_BGR2GRAY)
        _, thresh = cv2.threshold(gray_diff, 30, 255, cv2.THRESH_BINARY)
        changed_pixels = np.count_nonzero(thresh)
        total_pixels = h * w
        change_ratio = changed_pixels / total_pixels

        # O'yin elementlarini aniqlash
        curr_analysis = analyze_frame(curr)

        # Action turini aniqlash
        if change_ratio < 0.001:
            action = 3  # NONE
            tx, ty = 0.5, 0.5
        elif change_ratio > 0.3:
            action = 1  # MOVE (ko'p o'zgarish -> harakat)
            tx, ty = 0.5, 0.5
        else:
            action = 0  # DOWN (teginish)
            if curr_analysis["skills"]:
                s = curr_analysis["skills"][0]
                tx, ty = s["x"] / w, s["y"] / h
            elif curr_analysis["attack"]:
                ax, ay = curr_analysis["attack"]
                tx, ty = ax / w, ay / h
            else:
                tx, ty = 0.5, 0.5

        labels.append({
            "coords": [tx, ty, tx, ty],
            "action": action,
        })

    return labels

# ─── Dataset tayyorlash ─────────────────────────────────────────────────────

def create_dataset(frames: list[np.ndarray], labels: list[dict], seq_len: int = SEQ_LEN):
    """Ketma-ket kadrlardan dataset yaratish"""
    X, y_coords, y_actions = [], [], []

    for i in range(len(frames) - seq_len):
        seq = frames[i:i + seq_len]
        label = labels[i + seq_len - 1] if i + seq_len - 1 < len(labels) else labels[-1]

        processed = []
        for frame in seq:
            resized = cv2.resize(frame, (IMG_SIZE, IMG_SIZE))
            normalized = resized.astype(np.float32) / 127.5 - 1.0
            processed.append(normalized)

        X.append(np.stack(processed, axis=0))
        y_coords.append(label["coords"])
        y_actions.append(label["action"])

    X = np.array(X, dtype=np.float32)
    y_coords = np.array(y_coords, dtype=np.float32)
    y_actions = np.array(y_actions, dtype=np.int32)

    # Action -> one-hot
    y_actions_onehot = np.zeros((len(y_actions), 4), dtype=np.float32)
    y_actions_onehot[np.arange(len(y_actions)), y_actions] = 1.0

    return X, y_coords, y_actions_onehot

# ─── Model arxitekturasi ────────────────────────────────────────────────────

def build_model(seq_len: int = SEQ_LEN, img_size: int = IMG_SIZE) -> Model:
    """CNN+LSTM — TFLiteModel.kt formati bilan mos"""
    # Input: (seq_len, img_size, img_size, 3)
    inputs = keras.Input(shape=(seq_len, img_size, img_size, 3))

    # CNN feature extraction (shared across timesteps)
    cnn = keras.Sequential([
        layers.Conv2D(32, (5, 5), strides=2, activation="relu", padding="same"),
        layers.BatchNormalization(),
        layers.MaxPooling2D((2, 2)),
        layers.Conv2D(64, (3, 3), strides=1, activation="relu", padding="same"),
        layers.BatchNormalization(),
        layers.MaxPooling2D((2, 2)),
        layers.Conv2D(128, (3, 3), strides=1, activation="relu", padding="same"),
        layers.BatchNormalization(),
        layers.GlobalAveragePooling2D(),
        layers.Dense(256, activation="relu"),
        layers.Dropout(0.3),
    ], name="cnn")

    # TimeDistributed CNN
    encoded = layers.TimeDistributed(cnn)(inputs)

    # LSTM sequence modeling
    lstm = layers.LSTM(256, return_sequences=False)(encoded)
    lstm = layers.Dropout(0.3)(lstm)

    # Output 1: coordinates [x1, y1, x2, y2]
    coords = layers.Dense(64, activation="relu")(lstm)
    coords = layers.Dense(4, activation="sigmoid", name="coordinates")(coords)

    # Output 2: action logits [DOWN, MOVE, UP, NONE]
    actions = layers.Dense(64, activation="relu")(lstm)
    actions = layers.Dense(4, activation="softmax", name="actions")(actions)

    model = Model(inputs=inputs, outputs=[coords, actions])

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.001),
        loss={
            "coordinates": "mse",
            "actions": "categorical_crossentropy",
        },
        loss_weights={
            "coordinates": 1.0,
            "actions": 2.0,
        },
        metrics={
            "coordinates": "mae",
            "actions": "accuracy",
        },
    )

    return model

# ─── .tflite eksport ────────────────────────────────────────────────────────

def export_tflite(model: Model, output_path: str):
    """Modelni TensorFlow Lite formatiga eksport qilish"""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]

    tflite_model = converter.convert()
    with open(output_path, "wb") as f:
        f.write(tflite_model)

    print(f"[INFO] .tflite fayl yaratildi: {output_path} ({len(tflite_model) / 1024:.1f} KB)")

# ─── Asosiy pipeline ────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="MLBB AI Trainer — YouTube orqali o'qitish")
    parser.add_argument("--urls", required=True, help="YouTube URLlar vergul bilan ajratilgan")
    parser.add_argument("--output", default="model.tflite", help="Chiqish .tflite fayli")
    parser.add_argument("--epochs", type=int, default=20, help="O'qitish epochlari soni")
    parser.add_argument("--batch-size", type=int, default=8, help="Batch hajmi")
    args = parser.parse_args()

    urls = [u.strip() for u in args.urls.split(",") if u.strip()]
    if not urls:
        print("[ERROR] Hech qanday URL berilmagan")
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="mlbb_train_")
    videos_dir = os.path.join(work_dir, "videos")
    frames_dir = os.path.join(work_dir, "frames")
    os.makedirs(videos_dir, exist_ok=True)
    os.makedirs(frames_dir, exist_ok=True)

    # 1. Video yuklab olish
    print("=" * 60)
    print("[1/5] YouTube videolarni yuklab olish")
    print("=" * 60)
    video_paths = download_videos(urls, videos_dir)
    if not video_paths:
        print("[ERROR] Video yuklab olinmadi")
        sys.exit(1)

    # 2. Kadrlarni ajratish
    print("=" * 60)
    print("[2/5] Kadrlarni ajratish")
    print("=" * 60)
    all_frames = []
    for vp in video_paths:
        all_frames.extend(extract_frames(vp, frames_dir))
    if len(all_frames) < SEQ_LEN + 1:
        print(f"[ERROR] Kadrlar soni yetarli emas ({len(all_frames)}), kamida {SEQ_LEN + 1} kerak")
        sys.exit(1)

    # 3. Kadrlarni yuklash va pseudo-label yaratish
    print("=" * 60)
    print("[3/5] Pseudo-label yaratish")
    print("=" * 60)
    loaded_frames = []
    for fp in tqdm(all_frames, desc="Kadrlar yuklanmoqda"):
        img = cv2.imread(fp)
        if img is not None:
            loaded_frames.append(img)
    print(f"{len(loaded_frames)} ta kadr yuklandi")

    pseudo_labels = create_pseudo_labels(loaded_frames)
    print(f"{len(pseudo_labels)} ta pseudo-label yaratildi")

    # 4. Dataset va model o'qitish
    print("=" * 60)
    print("[4/5] Model o'qitish")
    print("=" * 60)
    X, y_coords, y_actions = create_dataset(loaded_frames, pseudo_labels)
    print(f"Dataset: X={X.shape}, y_coords={y_coords.shape}, y_actions={y_actions.shape}")

    model = build_model()
    model.summary()

    # Train/validation split
    split = int(len(X) * 0.8)
    X_train, X_val = X[:split], X[split:]
    yc_train, yc_val = y_coords[:split], y_coords[split:]
    ya_train, ya_val = y_actions[:split], y_actions[split:]

    early_stop = keras.callbacks.EarlyStopping(
        monitor="val_loss", patience=5, restore_best_weights=True
    )

    model.fit(
        X_train, {"coordinates": yc_train, "actions": ya_train},
        validation_data=(X_val, {"coordinates": yc_val, "actions": ya_val}),
        epochs=args.epochs,
        batch_size=args.batch_size,
        callbacks=[early_stop],
        verbose=1,
    )

    # 5. .tflite eksport
    print("=" * 60)
    print("[5/5] .tflite eksport")
    print("=" * 60)
    export_tflite(model, args.output)

    print(f"\n✅ Model tayyor: {args.output}")
    print(f"   Buni telefonda import qiling:")
    print(f"   Qahramon tafsilotlari > Import Trained Model (.tflite)")

if __name__ == "__main__":
    main()
