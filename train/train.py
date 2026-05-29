"""
MLBB AI Trainer — YouTube orqali model o'qitish

Pipeline:
  1. YouTube videolarni yuklab olish (yt_dlp library)
  2. Kadrlarni ajratib olish
  3. Pseudo-label yaratish (frame farqlari orqali)
  4. CNN+LSTM model o'qitish
  5. .tflite ga eksport qilish

Foydalanish:
  python train.py --urls "URL1,URL2,URL3" --output model.tflite
"""

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers, Model
from tqdm import tqdm

# ─── Konstantalar ───────────────────────────────────────────────────────────
SEQ_LEN = 4
IMG_SIZE = 224
FPS = 10

def download_video(url, out_dir):
    """yt_dlp library orqali video yuklab olish — bir necha usul bilan"""
    try:
        from yt_dlp import YoutubeDL
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "yt-dlp"], check=True)
        from yt_dlp import YoutubeDL

    out_template = os.path.join(out_dir, "%(id)s.%(ext)s")

    # Bir necha extractor strategiyalarni sinab ko'rish
    strategies = [
        {"extractor_args": {"youtube": {"player_client": ["android"]}}},
        {"extractor_args": {"youtube": {"player_client": ["android", "web"]}}},
        {"extractor_args": {"youtube": {"skip": ["webpage"]}}},
        {},
    ]

    for i, extra_opts in enumerate(strategies):
        try:
            ydl_opts = {
                "format": "mp4/bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "outtmpl": out_template,
                "quiet": True,
                "no_warnings": True,
                "geo_bypass": True,
                "nocheckcertificate": True,
                "retries": 5,
                "fragment_retries": 5,
                "extractor_retries": 3,
                "ignoreerrors": False,
            }
            ydl_opts.update(extra_opts)

            with YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                video_id = info.get("id", "unknown")
                ext = info.get("ext", "mp4")
                expected = os.path.join(out_dir, f"{video_id}.{ext}")

                # Turli extensionlarni tekshirish
                for f in os.listdir(out_dir):
                    if f.startswith(video_id):
                        return os.path.join(out_dir, f)

                if os.path.exists(expected):
                    return expected

                # Hech qanday fayl topilmasa
                print(f"[WARN] Strategy {i+1}: fayl topilmadi, boshqa usul sinanmoqda...")

        except Exception as e:
            if i < len(strategies) - 1:
                print(f"[WARN] Strategy {i+1} failed ({e}), trying next...")
            else:
                raise

    raise RuntimeError(f"Video yuklab olinmadi: {url}")


def extract_frames(video_path, out_dir, fps=FPS):
    """Kadrlarni ajratish"""
    os.makedirs(out_dir, exist_ok=True)
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"Video ochilmadi: {video_path}")

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
    print(f"  {len(frames)} ta kadr ajratildi")
    return frames


def create_pseudo_labels(frames):
    """Frame farqlari orqali pseudo-label yaratish"""
    labels = []
    for i in range(len(frames) - 1):
        curr = frames[i]
        nxt = frames[i + 1]
        h, w = curr.shape[:2]

        diff = cv2.absdiff(curr, nxt)
        gray_diff = cv2.cvtColor(diff, cv2.COLOR_BGR2GRAY)
        _, thresh = cv2.threshold(gray_diff, 30, 255, cv2.THRESH_BINARY)
        changed = np.count_nonzero(thresh)
        change_ratio = changed / (h * w)

        if change_ratio < 0.001:
            action = 3
            tx, ty = 0.5, 0.5
        elif change_ratio > 0.3:
            action = 1
            tx, ty = 0.5, 0.5
        elif change_ratio > 0.05:
            action = 0
            tx, ty = 0.5, 0.5
        else:
            action = 2
            tx, ty = 0.3 + np.random.random() * 0.4, 0.3 + np.random.random() * 0.4

        labels.append({
            "coords": [tx, ty, tx, ty],
            "action": action,
        })
    return labels


def create_dataset(frames, labels, seq_len=SEQ_LEN):
    """Dataset yaratish"""
    X, y_coords, y_actions = [], [], []

    for i in range(len(frames) - seq_len):
        seq = frames[i:i + seq_len]
        label = labels[i + seq_len - 1] if i + seq_len - 1 < len(labels) else labels[-1]

        processed = []
        for frame in seq:
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            resized = cv2.resize(rgb, (IMG_SIZE, IMG_SIZE))
            normalized = resized.astype(np.float32) / 127.5 - 1.0
            processed.append(normalized)

        X.append(np.stack(processed, axis=0))
        y_coords.append(label["coords"])
        y_actions.append(label["action"])

    X = np.array(X, dtype=np.float32)
    y_coords = np.array(y_coords, dtype=np.float32)
    y_actions = np.array(y_actions, dtype=np.int32)

    y_actions_onehot = np.zeros((len(y_actions), 4), dtype=np.float32)
    y_actions_onehot[np.arange(len(y_actions)), y_actions] = 1.0

    return X, y_coords, y_actions_onehot


def build_model(seq_len=SEQ_LEN, img_size=IMG_SIZE):
    """CNN+LSTM — TFLiteModel.kt bilan mos"""
    inputs = keras.Input(shape=(seq_len, img_size, img_size, 3))

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

    encoded = layers.TimeDistributed(cnn)(inputs)
    lstm = layers.LSTM(256, return_sequences=False)(encoded)
    lstm = layers.Dropout(0.3)(lstm)

    coords = layers.Dense(64, activation="relu")(lstm)
    coords = layers.Dense(4, activation="sigmoid", name="coordinates")(coords)

    actions = layers.Dense(64, activation="relu")(lstm)
    actions = layers.Dense(4, activation="softmax", name="actions")(actions)

    model = Model(inputs=inputs, outputs=[coords, actions])

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.001),
        loss={"coordinates": "mse", "actions": "categorical_crossentropy"},
        loss_weights={"coordinates": 1.0, "actions": 2.0},
        metrics={"coordinates": "mae", "actions": "accuracy"},
    )

    return model


def export_tflite(model, output_path):
    """.tflite ga eksport"""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]

    tflite_model = converter.convert()
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(tflite_model)

    print(f"[INFO] .tflite fayl yaratildi: {output_path} ({len(tflite_model) / 1024:.1f} KB)")


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
    video_paths = []
    for url in urls:
        print(f"  Yuklab olinmoqda: {url}")
        try:
            path = download_video(url, videos_dir)
            video_paths.append(path)
            print(f"  ✓ {os.path.basename(path)}")
        except Exception as e:
            print(f"  ✗ Yuklab olinmadi: {e}")
            if len(video_paths) == 0:
                print("[ERROR] Hech qanday video yuklab olinmadi")
                sys.exit(1)
            print("  Boshqa videoga o'tilmoqda...")

    # 2. Kadrlarni ajratish
    print("=" * 60)
    print("[2/5] Kadrlarni ajratish")
    print("=" * 60)
    all_frames = []
    for vp in video_paths:
        all_frames.extend(extract_frames(vp, frames_dir))
    if len(all_frames) < SEQ_LEN + 1:
        print(f"[ERROR] Kadrlar soni yetarli emas ({len(all_frames)})")
        sys.exit(1)

    # 3. Kadrlarni yuklash
    print("=" * 60)
    print("[3/5] Pseudo-label yaratish")
    print("=" * 60)
    loaded = []
    for fp in tqdm(all_frames, desc="Kadrlar"):
        img = cv2.imread(fp)
        if img is not None:
            loaded.append(img)
    print(f"  {len(loaded)} ta kadr yuklandi")

    pseudo_labels = create_pseudo_labels(loaded)
    print(f"  {len(pseudo_labels)} ta pseudo-label yaratildi")

    # 4. O'qitish
    print("=" * 60)
    print("[4/5] Model o'qitish")
    print("=" * 60)
    X, y_coords, y_actions = create_dataset(loaded, pseudo_labels)
    print(f"  Dataset: X={X.shape}")

    model = build_model()
    model.summary()

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

    # 5. Eksport
    print("=" * 60)
    print("[5/5] .tflite eksport")
    print("=" * 60)
    export_tflite(model, args.output)

    print(f"\n✅ Model tayyor: {args.output}")


if __name__ == "__main__":
    main()
