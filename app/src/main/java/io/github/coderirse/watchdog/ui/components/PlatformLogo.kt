package io.github.coderirse.watchdog.ui.components

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
import io.github.coderirse.watchdog.data.model.PlatformType

/**
 * 平台Logo：网络加载官方Logo，失败时显示品牌色缩写
 *
 * @param backgroundColor 底圆形容器色，默认品牌色 12% 底；置于品牌渐变卡上时传半透白
 * @param initialsColor 缩写文字颜色，默认品牌色；置于品牌渐变卡上时传白色
 */
@Composable
fun PlatformLogo(
    platform: PlatformType,
    modifier: Modifier = Modifier,
    size: Int = 36,
    backgroundColor: androidx.compose.ui.graphics.Color = platform.visual.brandColor.copy(alpha = 0.12f),
    initialsColor: androidx.compose.ui.graphics.Color = platform.visual.brandColor
) {
    val isPreview = LocalInspectionMode.current

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (isPreview) {
            // 预览模式：直接显示缩写
            LogoInitials(platform.visual.initials, initialsColor, size)
        } else {
            // 生产模式：网络加载Logo，失败回退缩写
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(platform.visual.logoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = platform.displayName,
                modifier = Modifier.size((size * 0.70).dp),
                contentScale = ContentScale.Fit
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading -> {
                        LogoInitials(platform.visual.initials, initialsColor, size)
                    }
                    is AsyncImagePainter.State.Error -> {
                        LogoInitials(platform.visual.initials, initialsColor, size)
                    }
                    is AsyncImagePainter.State.Success -> {
                        // 加载成功，只显示Logo（不显示缩写）
                        SubcomposeAsyncImageContent()
                    }
                    else -> {
                        LogoInitials(platform.visual.initials, initialsColor, size)
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
