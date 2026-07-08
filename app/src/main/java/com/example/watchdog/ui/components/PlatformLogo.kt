package com.example.watchdog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.example.watchdog.data.model.PlatformType

/**
 * 平台Logo：网络加载官方Logo，失败时显示品牌色缩写
 */
@Composable
fun PlatformLogo(
    platform: PlatformType,
    modifier: Modifier = Modifier,
    size: Int = 36
) {
    val isPreview = LocalInspectionMode.current

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(platform.brandColor.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        if (isPreview) {
            // 预览模式：直接显示缩写
            LogoInitials(platform.initials, platform.brandColor, size)
        } else {
            // 生产模式：网络加载Logo，失败回退缩写
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(platform.logoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = platform.displayName,
                modifier = Modifier.size((size * 0.70).dp),
                contentScale = ContentScale.Fit
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading -> {
                        LogoInitials(platform.initials, platform.brandColor, size)
                    }
                    is AsyncImagePainter.State.Error -> {
                        LogoInitials(platform.initials, platform.brandColor, size)
                    }
                    is AsyncImagePainter.State.Success -> {
                        // 加载成功，只显示Logo（不显示缩写）
                        SubcomposeAsyncImageContent()
                    }
                    else -> {
                        LogoInitials(platform.initials, platform.brandColor, size)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogoInitials(initials: String, color: androidx.compose.ui.graphics.Color, size: Int) {
    Text(
        text = initials,
        fontSize = (size * 0.35).sp,
        fontWeight = FontWeight.Bold,
        color = color,
        textAlign = TextAlign.Center
    )
}
