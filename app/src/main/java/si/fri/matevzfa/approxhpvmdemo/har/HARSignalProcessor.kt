package si.fri.matevzfa.approxhpvmdemo.har

import uk.me.berndporr.iirj.Butterworth
import uk.me.berndporr.iirj.Cascade

/**
 * An actor object that transforms raw signal data into signal images.
 *
 * @param axisMapping is used for reordering axes x,y,z
 */
class HARSignalProcessor(axisMapping: Map<Int, Int>) {

    object AxisOrder {
        val DEFAULT = mapOf(
            0 to 0,
            1 to 1,
            2 to 2
        )

        val PORTRAIT_PHONE = mapOf(
            0 to 1,
            1 to 2,
            2 to 0
        )
    }

    data class SensorData(
        val x: Float,
        val y: Float,
        val z: Float,
    )

    class SamplingError(message: String) : Exception(message)

    /* Sampling parameters */
    val numReads = 128
    val rate50Hz = 20_000

    /* Intermediate sensor data containers */
    private val accelContainer = DataContainer(numReads, 3, axisMapping)
    private val gyroContainer = DataContainer(numReads, 3, axisMapping)

    /* Butterworth filters for filtering sensor signals */
    private val filterAccel20HzX = lowPass20Hz(rate50Hz)
    private val filterAccel20HzY = lowPass20Hz(rate50Hz)
    private val filterAccel20HzZ = lowPass20Hz(rate50Hz)

    private val filterGyro20HzX = lowPass20Hz(rate50Hz)
    private val filterGyro20HzY = lowPass20Hz(rate50Hz)
    private val filterGyro20HzZ = lowPass20Hz(rate50Hz)

    private val filterAccel03HzX = lowPass03Hz(rate50Hz)
    private val filterAccel03HzY = lowPass03Hz(rate50Hz)
    private val filterAccel03HzZ = lowPass03Hz(rate50Hz)

    /**
     * Clears all buffers that hold sensor data
     */
    fun reset() {
        accelContainer.reset()
        gyroContainer.reset()
    }

    fun isFull(): Boolean = !needsAccData() && !needsGyrData()

    fun needsAccData(): Boolean = !accelContainer.isFull()

    fun addAccData(accData: SensorData) {
        if (!needsAccData()) {
            throw SamplingError("Accelerometer data is already sufficient")
        }

        accelContainer.push(floatArrayOf(accData.x, accData.y, accData.z))
    }

    fun needsGyrData(): Boolean = !gyroContainer.isFull()

    fun addGyrData(gyrData: SensorData) {
        if (!needsGyrData()) {
            throw SamplingError("Gyroscope data is already sufficient")
        }

        gyroContainer.push(floatArrayOf(gyrData.x, gyrData.y, gyrData.z))
    }

    /**
     * Applies required pre-processing of sensor data, resets data collection and returns the signal
     * image.
     */
    fun signalImage(): FloatArray {
        if (needsAccData()) {
            throw SamplingError("getSignalImageAndReset called with missing accelerometer data")
        }

        if (needsGyrData()) {
            throw SamplingError("getSignalImageAndReset called with missing accelerometer data")
        }

        val totalAccelData = copyAndMedianFilter(accelContainer.dataRef(), 1)
        val gyroData = copyAndMedianFilter(gyroContainer.dataRef(), 1)

        // Data has been copied, reset the containers
        accelContainer.reset()
        gyroContainer.reset()

        // Convert to 'g's, because android uses m/s??
        for (i in totalAccelData.indices) {
            totalAccelData[i] /= 9.807f
        }

        // Prepare total_acc
        filterWith(totalAccelData, 0, filterAccel20HzX)
        filterWith(totalAccelData, 1, filterAccel20HzY)
        filterWith(totalAccelData, 2, filterAccel20HzZ)

        // Prepare body_acc
        // At first, we filter the data so that bodyAccelData actually contains
        // gravity acceleration, but we later subtract it from totalAccelData to obtain
        // actual bodyAccelData.
        val bodyAccelData = totalAccelData.copyOf()
        filterWith(bodyAccelData, 0, filterAccel03HzX)
        filterWith(bodyAccelData, 1, filterAccel03HzY)
        filterWith(bodyAccelData, 2, filterAccel03HzZ)
        for (i in bodyAccelData.indices) {
            bodyAccelData[i] = totalAccelData[i] - bodyAccelData[i]
        }

        // Prepare gyro_acc
        filterWith(gyroData, 0, filterGyro20HzX)
        filterWith(gyroData, 1, filterGyro20HzY)
        filterWith(gyroData, 2, filterGyro20HzZ)

        // Prepare signal image
        val signalImage = FloatArray(3 * 32 * 32)
        fillSignalImageChannel(signalImage, 0, bodyAccelData)
        fillSignalImageChannel(signalImage, 1, gyroData)
        fillSignalImageChannel(signalImage, 2, totalAccelData)

        return signalImage
    }

    /**
     * [DataContainer] serves for intermediate storage for sensor data.
     * It is used to read [numReads]  sensor readings with [numAxes] channels (e.g. x, y, z values of
     * gyroscope data).
     */
    private class DataContainer(
        val numReads: Int,
        val numAxes: Int,
        val axisMapping: Map<Int, Int>,
    ) {

        private var idx: Int = 0
        private var data = FloatArray(numReads * numAxes)

        /**
         * Add [numAxes] values to the container
         *
         * @throws IndexOutOfBoundsException if [v] does not have at least [numAxes] elements, or if
         * more than [numReads] values are added
         */
        fun push(v: FloatArray) {
            for (i in 0 until numAxes) {
                data[idx++] = v[axisMapping.getOrDefault(i, i)]
            }
        }

        fun isFull(): Boolean = idx >= data.size

        fun reset() {
            idx = 0
        }

        /**
         * Accessor method for [data] field of this data structure. Data is **NOT** copied.
         */
        fun dataRef(): FloatArray = data
    }

    /**
     * Applies median filtering to [values], with specified |winSize]. [winSize] is interpreted as the
     * offset in each direction. Actual window size is computed by `2*winSize+1`.
     * For Out-of-bounds values, the nearest valid value is used.
     *
     * The [values] array is an array composed of values of all three axes:
     * `[x, y, z, x, y, z, ... x, y, z]`. The implementation filters each axis separately
     */
    private fun copyAndMedianFilter(values: FloatArray, winSize: Int): FloatArray {
        val window = FloatArray(2 * winSize + 1)
        val out = FloatArray(values.size)

        for (axis in 0..2) {
            val axisMinIdx = axis
            val axisMaxIdx = values.size - 3 + axis
            for (i in axis until values.size step 3) {
                for (ir in 0 until (2 * winSize + 1)) {
                    val idx = i - (winSize + ir) * 3
                    val clippedIdx = Integer.min(axisMaxIdx, Integer.max(axisMinIdx, idx))
                    window[ir] = values[clippedIdx]
                }
                window.sort()
                out[i] = window[winSize]
            }
        }

        return out
    }

    /**
     * Fills [channel] of [out] with data given by [channelData].
     *
     * The data in each channel is represented as follows:
     *
     * ```
     * [
     *   8 rows for d in [x, y, z, y, x, z, x, y]:
     *      channel.d[0..32]
     *   8 rows for d in [x, y, z, y, x, z, x, y]:
     *      channel.d[32..64]
     *   8 rows for d in [x, y, z, y, x, z, x, y]:
     *      channel.d[64..96]
     *   8 rows for d in [x, y, z, y, x, z, x, y]:
     *      channel.d[96..128]
     * ]
     * ```
     */
    private fun fillSignalImageChannel(
        out: FloatArray,
        channel: Int,
        channelData: FloatArray
    ) {
        fun FloatArray.getAxis(i: Int, axis: Int) = this[3 * i + axis]

        val chanOffset = channel * 32 * 32

        // .................. x, y, z, y, x, z, x, y
        val outAxes = arrayOf(0, 1, 2, 1, 0, 2, 0, 1)

        for (row in 0 until 32) {
            val ax = outAxes[row % 8]
            val fold = row / 8
            for (col in 0 until 32) {
                out[chanOffset + row * 32 + col] = channelData.getAxis(fold * 32 + col, ax)
            }
        }
    }

    /**
     * Filter [axis] component of [data] in-place with the given [filter].
     */
    private fun filterWith(data: FloatArray, axis: Int, filter: Cascade) {
        for (i in axis until data.size step 3) {
            data[i] = filter.filter(data[i].toDouble()).toFloat()
        }
    }

    /**
     * Returns a low-pass Butterworth filter of 3rd order with cut-off frequency of 20Hz.
     * Sampling frequency [sampRateUs] must be given in microseconds.
     */
    private fun lowPass20Hz(sampRateUs: Int) = Butterworth().apply {
        lowPass(3, 1_000_000.0 / sampRateUs, 20.0)
    }

    /**
     * Returns a low-pass Butterworth filter of 3rd order with cut-off frequency of 0.3Hz.
     * Sampling frequency [sampRateUs] must be given in microseconds.
     */
    private fun lowPass03Hz(sampRateUs: Int) = Butterworth().apply {
        lowPass(3, 1_000_000.0 / sampRateUs, 0.3)
    }
}
