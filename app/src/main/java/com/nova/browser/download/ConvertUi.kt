package com.nova.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
fun ConvertOfferDialog() {
    val t = Downloader.offer ?: return
    val ctx = LocalContext.current
    NovaDialog(
        title = stringResource(R.string.conv_offer_title),
        icon = Icons.Default.Check,
        onDismiss = { Downloader.offer = null },
        confirmText = stringResource(R.string.conv_yes),
        onConfirm = { Downloader.offer = null; AudioConvert.toMp3(ctx.applicationContext, t) },
        dismissText = stringResource(R.string.conv_no)
    ) { DialogText(t.name + "\n" + stringResource(R.string.conv_offer_text)) }
}
