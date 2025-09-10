# Model FaceEmbedding from https://huggingface.co/garavv/arcface-onnx

## Tải model
```
cd .converter
curl -L "https://huggingface.co/garavv/arcface-onnx/resolve/main/arc.onnx?download=true" -o arcface.onnx
```
## Setup (mấy thư viện onnx related sẽ conflict tùm lum nếu k chọn đúng)
``` 
python -m venv .venv
.venv\scripts\activate              # windows cmd 
pip install -r requirements.txt     

# requirements.txt thu được khi `pip freeze` trong venv đã active để tránh xung đột
```
## Chuẩn bị kaggle
- Vào https://www.kaggle.com/settings -> API -> Create new token (tải file kaggle.json)
- `mkdir kaggle && setx KAGGLE_CONFIG_DIR \path\to\project\UVCTestCamera\.converter\.kaggle`   
- Chuyển file kaggle.json vào `.converter\.kaggle`

## Chuyển đổi model 
```
# Chuyển model từ arcface.onnx ban đầu thành arcface_tf

onnx-tf convert -i arcface.onnx -o arcface_tf


# Tải dataset lfw-dataset từ kaggle (calibration dataset) 
# Dùng để hiệu chuẩn khi convert từ output embedding vector float32[512] thành int8[512]

python calib_dataset.py


# Chuyển đổi model sang tflite int8
python convert.py
```