import os
import cv2
import numpy as np
import kaggle

CALIB_DIR = "calib_images"
IMG_SIZE = (112, 112)

def download_kaggle_dataset(dataset_url, output_path, unzip=True):
    try:
        os.makedirs(output_path, exist_ok=True)
        kaggle.api.dataset_download_files(dataset_url, path=output_path, unzip=unzip)
        
        print(f"Dataset '{dataset_url}' downloaded successfully to '{output_path}'!")
        return True
    except Exception as e:
        print(f"Error downloading dataset '{dataset_url}': {str(e)}")
        return False

def load_calib_dataset(max_images=500):
    if not os.path.exists(CALIB_DIR) or not os.listdir(CALIB_DIR):
        print(f"Directory '{CALIB_DIR}' is empty or does not exist. Downloading dataset...")
        dataset_url = "jessicali9530/lfw-dataset"
        download_kaggle_dataset(dataset_url, CALIB_DIR, unzip=True)

    calib_dataset = []
    files = []
    for root, _, fs in os.walk(CALIB_DIR):
        for f in fs:
            if f.lower().endswith(('.jpg', '.png')):
                files.append(os.path.join(root, f))

    for path in files[:max_images]:
        img = cv2.imread(path)
        if img is None:
            continue
        img = cv2.resize(img, IMG_SIZE)
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        img = img.astype(np.float32) / 255.0
        calib_dataset.append(img)

    return calib_dataset

def representative_data_gen():
    calib_dataset = load_calib_dataset()
    for img in calib_dataset:
        yield [np.expand_dims(img, axis=0).astype(np.float32)]

if __name__ == "__main__":
    dataset = load_calib_dataset()
    print(f"Loaded {len(dataset)} images from the dataset.")