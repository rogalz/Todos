package com.example.ai.ui.icons

import ai.shared.generated.resources.Res
import ai.shared.generated.resources.ic_cloud_done
import ai.shared.generated.resources.ic_cloud_off
import ai.shared.generated.resources.ic_cloud_upload
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource

/**
 * Icons that `material-icons-core` does not have (the cloud family), as vector drawables in
 * `composeResources/drawable`. Everything else comes from `Icons.Default`. Tinted by the caller.
 */
internal object AppIcons {
    /** Everything has reached the server. */
    val CloudDone: Painter @Composable get() = painterResource(Res.drawable.ic_cloud_done)

    /** The server cannot be reached. */
    val CloudOff: Painter @Composable get() = painterResource(Res.drawable.ic_cloud_off)

    /** A local change that is waiting to be uploaded. */
    val CloudUpload: Painter @Composable get() = painterResource(Res.drawable.ic_cloud_upload)
}
