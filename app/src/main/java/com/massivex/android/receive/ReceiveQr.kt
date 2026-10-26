package com.massivex.android.receive

import com.massivex.massa.MassaCodec
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Plain canonical public AU address, matching iOS ReceivePayload. No URI or network implication. */
object ReceiveQr {
    fun matrix(address: String, pixels: Int = 256): BitMatrix {
        MassaCodec.validateAddress(address, userOnly = true)
        require(pixels in 128..1024)
        return QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, pixels, pixels,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
    }
    fun pixels(address: String, size: Int = 256): IntArray {
        val matrix = matrix(address, size)
        return IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
    }
}
