package com.example.sensegallery;

import org.junit.Test;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.NodeInfo;

import java.io.File;
import java.util.Map;

public class ExampleUnitTest {
    @Test
    public void testOnnx() throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        File modelFile = new File("src/main/assets/det_10g.onnx");
        if (!modelFile.exists()) {
            System.out.println("Model not found at: " + modelFile.getAbsolutePath());
            return;
        }
        OrtSession session = env.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
        System.out.println("Inputs:");
        for (Map.Entry<String, NodeInfo> e : session.getInputInfo().entrySet()) {
            System.out.println(e.getKey() + ": " + e.getValue().getInfo().toString());
        }
        System.out.println("Outputs:");
        for (Map.Entry<String, NodeInfo> e : session.getOutputInfo().entrySet()) {
            System.out.println(e.getKey() + ": " + e.getValue().getInfo().toString());
        }
    }
}