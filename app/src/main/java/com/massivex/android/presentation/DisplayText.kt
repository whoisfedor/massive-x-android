package com.massivex.android.presentation

import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Display only; never reused to parse amounts, receipts, drafts or protocol serialization. */
fun localizedDecimal(canonical:String,locale:Locale):String=canonical.replace('.',DecimalFormatSymbols.getInstance(locale).decimalSeparator)
fun localizedTime(millis:Long,locale:Locale):String=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
