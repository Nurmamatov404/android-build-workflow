"""
MLBB AI Trainer - YouTube orqali model o'qitish

Pipeline:
  1. YouTube videolarni yuklab olish (yt_dlp/gdown/requests)
  2. Kadrlarni ajratib olish
  3a. Pseudo-label yaratish (frame farqlari orqali) -> Action model (CNN+LSTM)
  3b. UI element pseudo-label yaratish (heuristic ScreenAnalyzer) -> YOLO model
  4. Model o'qitish (action model + YOLO detection)
  5. .tflite ga eksport qilish

Foydalanish:
  python train.py --urls "URL1,URL2" --output model.tflite
"""

import argparse
import json
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

# --- Konstantalar ---
SEQ_LEN = 4
IMG_SIZE = 224
YOLO_SIZE = 416
FPS = 10
NUM_CLASSES = 8  # joystick, skill1, skill2, skill3, ultimate, attack, recall, minimap
YOLO_LABELS = [
    "joystick", "skill1", "skill2", "skill3",
    "ultimate", "attack", "recall", "minimap"
]
GRID_SIZE = YOLO_SIZE // 32  # 13


def is_youtube(url):
    return "youtube.com" in url or "youtu.be" in url


def is_google_drive(url):
    return "drive.google.com" in url or "docs.google.com" in url


def download_video(url, out_dir):
    os.makedirs(out_dir, exist_ok=True)

    if is_youtube(url):
        return _download_youtube(url, out_dir)
    elif is_google_drive(url):
        return _download_drive(url, out_dir)
    else:
        return _download_direct(url, out_dir)


def _download_youtube(url, out_dir):
    try:
        from yt_dlp import YoutubeDL
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "yt-dlp"], check=True)
        from yt_dlp import YoutubeDL

    out_template = os.path.join(out_dir, "%(id)s.%(ext)s")
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
                for f in os.listdir(out_dir):
                    if f.startswith(video_id):
                        return os.path.join(out_dir, f)

        except Exception as e:
            if i < len(strategies) - 1:
                print(f"[WARN] YouTube strategy {i+1} failed ({e}), trying next...")
            else:
                raise

    raise RuntimeError(f"YouTube video yuklab olinmadi: {url}")


def _download_drive(url, out_dir):
    try:
        import gdown
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "gdown"], check=True)
        import gdown

    print(f"[INFO] Google Drive dan yuklanmoqda: {url}")

    file_id = None
    if "/file/d/" in url:
        file_id = url.split("/file/d/")[1].split("/")[0]
    elif "id=" in url:
        file_id = url.split("id=")[1].split("&")[0]
    elif "export=download" in url and "id=" in url:
        file_id = url.split("id=")[1].split("&")[0]

    if file_id:
        output = os.path.join(out_dir, f"drive_{file_id}.mp4")
        gdown.download(id=file_id, output=output, quiet=False)
    else:
        output = os.path.join(out_dir, "drive_video.mp4")
        gdown.download(url, output=output, quiet=False)
    if not os.path.exists(output):
        raise RuntimeError(f"Google Drive dan yuklab olinmadi: {url}")
    return output


def _download_direct(url, out_dir):
    import requests
    fname = url.split("/")[-1].split("?")[0] or "direct_video.mp4"
    output = os.path.join(out_dir, fname)

    print(f"[INFO] To'g'ridan-to'g'ri yuklanmoqda: {url}")
    resp = requests.get(url, stream=True, timeout=300, headers={
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
    })
    resp.raise_for_status()

    total = int(resp.headers.get("content-length", 0))
    with open(output, "wb") as f:
        if total:
            with tqdm(total=total, unit="B", unit_scale=True, desc=fname) as pbar:
                for chunk in resp.iter_content(chunk_size=8192):
                    f.write(chunk)
                    pbar.update(len(chunk))
        else:
            for chunk in resp.iter_content(chunk_size=8192):
                f.write(chunk)

    if not os.path.exists(output) or os.path.getsize(output) == 0:
        raise RuntimeError(f"URL dan yuklab olinmadi: {url}")
    return output


def extract_frames(video_path, out_dir, fps=FPS):
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


# ============ ACTION MODEL (CNN+LSTM) ============

def create_pseudo_labels(frames):
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


def build_action_model(seq_len=SEQ_LEN, img_size=IMG_SIZE):
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


# ============ YOLO DETECTION MODEL ============

def _heuristic_detect_ui(frame):
    """Heuristic ScreenAnalyzer logic -> YOLO pseudo-labels"""
    h, w = frame.shape[:2]
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    boxes = []

    # Joystick: bottom-left, dark/green area
    bottom_left = frame[h*2//3:, :w//2, :]
    dark_mask = np.all(bottom_left < 80, axis=2)
    green_mask = (hsv[h*2//3:, :w//2, 0] > 40) & (hsv[h*2//3:, :w//2, 0] < 90) & \
                 (hsv[h*2//3:, :w//2, 1] > 40)
    joystick_mask = (dark_mask | green_mask).astype(np.uint8) * 255
    if np.count_nonzero(joystick_mask) > 500:
        ys, xs = np.where(joystick_mask)
        cy = int(np.mean(ys)) + h*2//3
        cx = int(np.mean(xs))
        size = max(int(np.std(xs) * 2.5), 60)
        boxes.append({"label": "joystick", "cx": cx / w, "cy": cy / h,
                       "bw": size / w, "bh": size / h})

    # Skill buttons: bottom-right, medium brightness, circular
    bottom_right = frame[h//2:, w*2//3:, :]
    brightness = cv2.cvtColor(bottom_right, cv2.COLOR_BGR2GRAY)
    skill_mask = (brightness > 30) & (brightness < 130)
    skill_mask = skill_mask.astype(np.uint8) * 255
    contours, _ = cv2.findContours(skill_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    skill_labels = ["skill1", "skill2", "skill3", "ultimate"]
    skill_idx = 0
    for cnt in sorted(contours, key=lambda c: cv2.contourArea(c), reverse=True):
        area = cv2.contourArea(cnt)
        if area < 200:
            continue
        x_c, y_c, bw_c, bh_c = cv2.boundingRect(cnt)
        cx = (x_c + bw_c/2) + w*2//3
        cy = (y_c + bh_c/2) + h//2
        if cy / h < 0.5 or cx / w < 0.6:
            continue
        label = skill_labels[skill_idx] if skill_idx < len(skill_labels) else "skill1"
        skill_idx += 1
        boxes.append({"label": label, "cx": cx / w, "cy": cy / h,
                       "bw": bw_c / w, "bh": bh_c / h})

    # Attack: bottom-right, bright area (avoiding skill positions)
    attack_mask = (brightness > 100).astype(np.uint8) * 255
    atk_contours, _ = cv2.findContours(attack_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for cnt in sorted(atk_contours, key=cv2.contourArea, reverse=True)[:5]:
        area = cv2.contourArea(cnt)
        if area < 100:
            continue
        x_c, y_c, bw_c, bh_c = cv2.boundingRect(cnt)
        cx = (x_c + bw_c/2) + w*2//3
        cy = (y_c + bh_c/2) + h//2
        if cx / w > 0.8 and cy / h > 0.75:
            overlap = any(abs(cx/w - b["cx"]) < 0.08 and abs(cy/h - b["cy"]) < 0.08 for b in boxes)
            if not overlap:
                boxes.append({"label": "attack", "cx": cx / w, "cy": cy / h,
                               "bw": bw_c / w, "bh": bh_c / h})
                break

    # Minimap: top-right, dark/blue
    top_right = frame[:h//3, w*9//10:, :]
    dark_blue_mask = (np.all(top_right < 60, axis=2) |
                      ((hsv[:h//3, w*9//10:, 0] > 100) & (hsv[:h//3, w*9//10:, 1] > 30)))
    dark_blue_mask = dark_blue_mask.astype(np.uint8) * 255
    if np.count_nonzero(dark_blue_mask) > 200:
        ys, xs = np.where(dark_blue_mask)
        cy = np.mean(ys) / 3
        cx = np.mean(xs) + w*9//10
        bw = (np.max(xs) - np.min(xs)) / w
        bh = (np.max(ys) - np.min(ys)) / h
        if bw > 0.01 and bh > 0.01:
            boxes.append({"label": "minimap", "cx": cx / w, "cy": cy / h,
                           "bw": bw, "bh": bh})

    # Recall: left-center
    left_center = frame[h//3:2*h//3, :w//4, :]
    recall_bright = cv2.cvtColor(left_center, cv2.COLOR_BGR2GRAY)
    _, recall_mask = cv2.threshold(recall_bright, 120, 255, cv2.THRESH_BINARY)
    recall_mask = recall_mask.astype(np.uint8)
    if np.count_nonzero(recall_mask) > 100:
        ys, xs = np.where(recall_mask)
        cy = np.mean(ys) + h//3
        cx = np.mean(xs)
        bw = (np.max(xs) - np.min(xs) + 20) / w
        bh = (np.max(ys) - np.min(ys) + 20) / h
        boxes.append({"label": "recall", "cx": cx / w, "cy": cy / h,
                       "bw": bw, "bh": bh})

    return boxes


def _frame_to_yolo_label(frame, grid_size=GRID_SIZE, num_classes=NUM_CLASSES):
    """Convert heuristic detections to YOLO grid label"""
    h, w = frame.shape[:2]
    boxes = _heuristic_detect_ui(frame)

    label_map = {name: i for i, name in enumerate(YOLO_LABELS)}
    label_tensor = np.zeros((grid_size, grid_size, num_classes + 5), dtype=np.float32)

    for box in boxes:
        label_name = box["label"]
        if label_name not in label_map:
            continue
        class_id = label_map[label_name]

        cx = box["cx"] * grid_size
        cy = box["cy"] * grid_size
        bw = box["bw"]
        bh = box["bh"]

        grid_x = int(np.clip(cx, 0, grid_size - 1))
        grid_y = int(np.clip(cy, 0, grid_size - 1))

        tx = cx - grid_x
        ty = cy - grid_y
        tw = np.log(max(bw * w / (YOLO_SIZE // 32), 1e-6))
        th = np.log(max(bh * h / (YOLO_SIZE // 32), 1e-6))

        label_tensor[grid_y, grid_x, 0] = tx
        label_tensor[grid_y, grid_x, 1] = ty
        label_tensor[grid_y, grid_x, 2] = tw
        label_tensor[grid_y, grid_x, 3] = th
        label_tensor[grid_y, grid_x, 4] = 1.0
        label_tensor[grid_y, grid_x, 5 + class_id] = 1.0

    return label_tensor


def create_yolo_dataset(frames):
    X_yolo, y_yolo = [], []
    for fp in tqdm(frames, desc="YOLO labels"):
        img = cv2.imread(fp)
        if img is None:
            continue
        rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(rgb, (YOLO_SIZE, YOLO_SIZE))
        normalized = resized.astype(np.float32) / 255.0
        X_yolo.append(normalized)
        y_yolo.append(_frame_to_yolo_label(img))
    return np.array(X_yolo, dtype=np.float32), np.array(y_yolo, dtype=np.float32)


def build_yolo_model(input_size=YOLO_SIZE, grid_size=GRID_SIZE, num_classes=NUM_CLASSES):
    """Tiny YOLO-like model for MLBB UI element detection"""
    inputs = keras.Input(shape=(input_size, input_size, 3))

    # Backbone
    x = layers.Conv2D(16, (3, 3), strides=2, padding="same", activation="relu")(inputs)
    x = layers.BatchNormalization()(x)

    x = layers.Conv2D(32, (3, 3), strides=2, padding="same", activation="relu")(x)
    x = layers.BatchNormalization()(x)

    x = layers.Conv2D(64, (3, 3), strides=2, padding="same", activation="relu")(x)
    x = layers.BatchNormalization()(x)

    x = layers.Conv2D(128, (3, 3), strides=2, padding="same", activation="relu")(x)
    x = layers.BatchNormalization()(x)

    x = layers.Conv2D(256, (3, 3), strides=2, padding="same", activation="relu")(x)
    x = layers.BatchNormalization()(x)

    # Detection head
    x = layers.Conv2D(128, (3, 3), padding="same", activation="relu")(x)
    x = layers.Conv2D(num_classes + 5, (1, 1), padding="same", activation="linear",
                       name="detection")(x)

    model = Model(inputs=inputs, outputs=x)

    def yolo_loss(y_true, y_pred):
        obj_mask = y_true[..., 4:5]
        coord_loss = tf.reduce_mean(obj_mask * tf.reduce_sum(
            tf.square(y_true[..., :4] - y_pred[..., :4]), axis=-1, keepdims=True))
        conf_loss = tf.reduce_mean(tf.square(
            obj_mask * y_true[..., 4:5] - obj_mask * tf.sigmoid(y_pred[..., 4:5])))
        class_loss = tf.reduce_mean(obj_mask * tf.reduce_sum(
            tf.square(y_true[..., 5:] - tf.nn.softmax(y_pred[..., 5:], axis=-1)), axis=-1, keepdims=True))
        return coord_loss + conf_loss + class_loss

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.001),
        loss=yolo_loss,
    )

    return model


def export_yolo_tflite(model, output_path):
    """YOLO modelni .tflite ga eksport qilish"""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    converter._experimental_lower_tensor_list_ops = False

    tflite_model = converter.convert()
    with open(output_path, "wb") as f:
        f.write(tflite_model)

    print(f"[INFO] YOLO .tflite yaratildi: {output_path} ({len(tflite_model) / 1024:.1f} KB)")


def export_tflite(model, output_path):
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    converter._experimental_lower_tensor_list_ops = False

    tflite_model = converter.convert()
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(tflite_model)

    print(f"[INFO] Action .tflite yaratildi: {output_path} ({len(tflite_model) / 1024:.1f} KB)")


def main():
    parser = argparse.ArgumentParser(description="MLBB AI Trainer")
    parser.add_argument("--urls", required=True, help="URL lar vergul bilan ajratilgan")
    parser.add_argument("--output", default="model.tflite", help="Chiqish .tflite fayli")
    parser.add_argument("--epochs", type=int, default=20, help="O'qitish epochlari")
    parser.add_argument("--yolo-epochs", type=int, default=30, help="YOLO o'qitish epochlari")
    parser.add_argument("--batch-size", type=int, default=8, help="Batch hajmi")
    parser.add_argument("--train-yolo", action="store_true", default=True,
                        help="YOLO model o'qitish (default: True)")
    args = parser.parse_args()

    urls = [u.strip() for u in args.urls.split(",") if u.strip()]
    if not urls:
        print("[ERROR] URL berilmagan")
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="mlbb_train_")
    videos_dir = os.path.join(work_dir, "videos")
    frames_dir = os.path.join(work_dir, "frames")
    os.makedirs(videos_dir, exist_ok=True)
    os.makedirs(frames_dir, exist_ok=True)

    # 1. Video yuklab olish
    print("=" * 60)
    print("[1/6] YouTube videolarni yuklab olish")
    print("=" * 60)
    video_paths = []
    for url in urls:
        print(f"  Yuklab olinmoqda: {url}")
        try:
            path = download_video(url, videos_dir)
            video_paths.append(path)
            print(f"  + {os.path.basename(path)}")
        except Exception as e:
            print(f"  X Yuklab olinmadi: {e}")
            if len(video_paths) == 0:
                print("[ERROR] Hech qanday video yuklab olinmadi")
                sys.exit(1)

    # 2. Kadrlarni ajratish
    print("=" * 60)
    print("[2/6] Kadrlarni ajratish")
    print("=" * 60)
    all_frames = []
    for vp in video_paths:
        all_frames.extend(extract_frames(vp, frames_dir))
    if len(all_frames) < SEQ_LEN + 1:
        print(f"[ERROR] Kadrlar soni yetarli emas ({len(all_frames)})")
        sys.exit(1)

    # Subsampling
    MAX_FRAMES = 1500
    if len(all_frames) > MAX_FRAMES:
        step = len(all_frames) // MAX_FRAMES
        all_frames = all_frames[::step][:MAX_FRAMES]
        print(f"  Subsampling: {len(all_frames)} ta kadr (step={step})")

    # 3. ACTION model training
    print("=" * 60)
    print("[3/6] Pseudo-label + Action model o'qitish")
    print("=" * 60)
    loaded = []
    for fp in tqdm(all_frames, desc="Kadrlarni yuklash"):
        img = cv2.imread(fp)
        if img is not None:
            loaded.append(img)
    print(f"  {len(loaded)} ta kadr yuklandi")

    pseudo_labels = create_pseudo_labels(loaded)
    print(f"  {len(pseudo_labels)} ta pseudo-label")

    X, y_coords, y_actions = create_dataset(loaded, pseudo_labels)
    print(f"  Dataset: X={X.shape}")

    action_model = build_action_model()
    action_model.summary()

    split = int(len(X) * 0.8)
    early_stop = keras.callbacks.EarlyStopping(
        monitor="val_loss", patience=5, restore_best_weights=True)

    action_model.fit(
        X[:split], {"coordinates": y_coords[:split], "actions": y_actions[:split]},
        validation_data=(X[split:], {"coordinates": y_coords[split:], "actions": y_actions[split:]}),
        epochs=args.epochs, batch_size=args.batch_size,
        callbacks=[early_stop], verbose=1,
    )

    # 4. YOLO model training
    output_base = args.output.replace(".tflite", "")
    yolo_output = f"{output_base}_yolo.tflite"

    if args.train_yolo:
        print("=" * 60)
        print("[4/6] YOLO UI element detection model")
        print("=" * 60)
        X_yolo, y_yolo = create_yolo_dataset(all_frames)
        print(f"  YOLO dataset: X={X_yolo.shape}")

        if len(X_yolo) > 10:
            yolo_model = build_yolo_model()
            yolo_model.summary()

            yolo_split = int(len(X_yolo) * 0.8)
            yolo_model.fit(
                X_yolo[:yolo_split], y_yolo[:yolo_split],
                validation_data=(X_yolo[yolo_split:], y_yolo[yolo_split:]),
                epochs=args.yolo_epochs, batch_size=args.batch_size,
                verbose=1,
            )

            export_yolo_tflite(yolo_model, yolo_output)
        else:
            print(f"[WARN] YOLO dataset juda kichik ({len(X_yolo)}), o'qitish o'tkazib yuborildi")
    else:
        print("[SKIP] YOLO model o'qitilmadi (--train-yolo=false)")

    # 5. Action model export
    action_output = args.output
    print("=" * 60)
    print("[5/6] Action model .tflite eksport")
    print("=" * 60)
    export_tflite(action_model, action_output)

    # 6. YOLO modelini action model papkasiga nusxalash
    if args.train_yolo and os.path.exists(yolo_output):
        print("=" * 60)
        print("[6/6] Yakunlash")
        print("=" * 60)
        print(f"  Action model: {action_output}")
        print(f"  YOLO model:   {yolo_output}")
    else:
        print(f"\nModel tayyor: {action_output}")


if __name__ == "__main__":
    main()
