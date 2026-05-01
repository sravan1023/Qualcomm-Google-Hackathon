package com.sightsense.core

object ModelCatalog {
    val visionDetectorCandidates = listOf(
        "vision/yolo26_det-tflite-float/yolo26_det.tflite"
    )

    const val visionLabelsAsset = "vision/yolo26_det-tflite-float/labels.txt"

    val audioClassifierCandidates = listOf(
        "audio/yamnet-tflite-w8a8/yamnet.tflite"
    )

    const val audioLabelsAsset = "audio/yamnet_class_map.txt"

    val llmCandidates = listOf(
        "llm/sightsense_fusion/model.litertlm",
        "llm/llm_chatbot_npu/model.litertlm"
    )
}