package me.ash.reader.ui.component.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.ui.component.base.RYDialog
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.showToast

@Composable
fun LinkActionDialog(
    visible: Boolean,
    linkData: LinkActionData?,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current

    RYDialog(
        visible = visible && linkData != null,
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = linkData?.linkText ?: stringResource(R.string.link_action_copy).substringBefore(" "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = linkData?.displayUrl(maxLength = 100) ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                LinkActionItem(
                    icon = Icons.Outlined.OpenInBrowser,
                    label = stringResource(R.string.open_in_browser),
                    onClick = {
                        onDismissRequest()
                        linkData?.let {
                            context.openURL(it.url, openLink, openLinkSpecificBrowser)
                        }
                    },
                )

                LinkActionItem(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.link_action_copy),
                    onClick = {
                        onDismissRequest()
                        linkData?.let {
                            copyToClipboard(context, it.url)
                        }
                    },
                )

                LinkActionItem(
                    icon = Icons.Outlined.Share,
                    label = stringResource(R.string.share),
                    onClick = {
                        onDismissRequest()
                        linkData?.let {
                            shareLink(context, it.url)
                        }
                    },
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun LinkActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Link", text)
    clipboard.setPrimaryClip(clip)
    context.showToast(context.getString(R.string.link_copied))
}

private fun shareLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, url)
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
}
