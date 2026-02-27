import tensorflow as tf
try:
    interpreter = tf.lite.Interpreter(model_path="app/src/main/assets/mobilefacenet.tflite")
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]
    print(f"INPUT: shape={input_details['shape']}, type={input_details['dtype']}")
    print(f"OUTPUT: shape={output_details['shape']}, type={output_details['dtype']}")
except Exception as e:
    print(f"ERROR: {e}")
