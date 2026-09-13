package com.yp.luminote.app.ui.about

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding

@Composable
fun AboutScreen(onBackClick: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val versionInfo = remember(context) {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
        "Version ${packageInfo.versionName ?: "Unknown"} (${packageInfo.longVersionCode})"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding()
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "‹",
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onBackClick),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                Text(
                    text = "About",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Luminote",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF79B8FF)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Edge lighting for alerts and ambient style.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFAFAFB8)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = versionInfo,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAFAFB8)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .luminoteSafeHorizontalPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AboutBlock(title = "About Luminote") {
                Text(
                    text = "Luminote brings notification lighting and ambient edge personalization " +
                        "to modern displays. It follows the physical contour of the screen while " +
                        "keeping the experience fast and unobtrusive.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFAFAFB8)
                )
            }

            AboutBlock(title = "Created by") {
                Text(
                    text = "Yurii Pasternak",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            AboutLink(
                title = "GitHub",
                subtitle = "github.com/pasternak2048",
                onClick = { uriHandler.openUri("https://github.com/pasternak2048") }
            )

            AboutLink(
                title = "Source code",
                subtitle = "yuriipasternak-luminote",
                onClick = {
                    uriHandler.openUri("https://github.com/pasternak2048/yuriipasternak-luminote")
                }
            )
        }
    }
}

@Composable
private fun AboutBlock(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF1B1B20))
            .border(1.dp, Color(0xFF303038), RoundedCornerShape(28.dp))
            .padding(20.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun AboutLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1B1B20))
            .border(1.dp, Color(0xFF303038), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAFAFB8)
            )
        }
        Text(
            text = "↗",
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF79B8FF)
        )
    }
}
