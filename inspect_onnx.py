import onnxruntime as ort

session = ort.InferenceSession('app/src/main/assets/det_10g.onnx')
print("Inputs:")
for i in session.get_inputs():
    print(f"Name: {i.name}, Shape: {i.shape}, Type: {i.type}")

print("\nOutputs:")
for o in session.get_outputs():
    print(f"Name: {o.name}, Shape: {o.shape}, Type: {o.type}")
