package com.canon.multicam.domain.session

interface CameraSessionController {
    suspend fun connect(): Result<Unit>
    fun startLiveView()
    fun stopLiveView()
    fun updateTargetFps(fps: Int)
    suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit>
    suspend fun setRecording(record: Boolean): Result<Unit>
    suspend fun triggerShutter(): Result<Unit>
    suspend fun setExposureSetting(
        iso: String? = null,
        shutterSpeed: String? = null,
        aperture: String? = null
    ): Result<Unit>
    fun release()
}
