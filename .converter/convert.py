import tensorflow as tf
from calib_dataset import representative_data_gen

converter = tf.lite.TFLiteConverter.from_saved_model("./arcface_tf")
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.representative_dataset = representative_data_gen
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
converter.inference_input_type = tf.int8
converter.inference_output_type = tf.int8

tflite_model = converter.convert()
with open("arcface_int8.tflite", "wb") as f:
    f.write(tflite_model)
