package io.github.mangi.eta.ui.components

/** 仅限制显示时解码的像素数；保存始终使用原始字节。 */
internal fun chatImageSampleSize(width: Int, height: Int, maxPixels: Long): Int {
    if (width <= 0 || height <= 0 || maxPixels <= 0) return 1
    var sample = 1
    while (sample < (1 shl 30) &&
        ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}
