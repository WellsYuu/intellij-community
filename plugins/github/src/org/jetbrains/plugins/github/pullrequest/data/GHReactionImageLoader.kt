// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.data

import com.intellij.collaboration.ui.icon.AsyncImageIconsProvider
import com.intellij.platform.util.coroutines.childScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiRequests
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.api.data.GHReactionContent
import org.jetbrains.plugins.github.api.executeSuspend
import java.awt.Image

internal class GHReactionImageLoader(
  parentCs: CoroutineScope,
  private val serverPath: GithubServerPath,
  private val requestExecutor: GithubApiRequestExecutor
) : AsyncImageIconsProvider.AsyncImageLoader<GHReactionContent> {
  private val cs = parentCs.childScope(CoroutineName("GitHub Reactions Image Loader"))

  private val emojisNameToUrl: Deferred<Map<String, String>> = cs.async(Dispatchers.IO) {
    requestExecutor.executeSuspend(GithubApiRequests.Emojis.loadNameToUrlMap(serverPath))
  }
  // seems to be a bug in github asset manager - loading emoji images in parallel sometimes leads to 404
  private val mutex = Mutex()

  override suspend fun load(key: GHReactionContent): Image? {
    val map = emojisNameToUrl.await()
    val url = map[key.emojiName] ?: return null
    return withContext(Dispatchers.IO) {
      mutex.withLock {
        requestExecutor.executeSuspend(GithubApiRequests.Emojis.loadImage(url))
      }
    }
  }
}

private val GHReactionContent.emojiName: String
  get() = when (this) {
    GHReactionContent.THUMBS_UP -> "+1"
    GHReactionContent.THUMBS_DOWN -> "-1"
    GHReactionContent.LAUGH -> "smile"
    GHReactionContent.HOORAY -> "tada"
    GHReactionContent.CONFUSED -> "confused"
    GHReactionContent.HEART -> "heart"
    GHReactionContent.ROCKET -> "rocket"
    GHReactionContent.EYES -> "eyes"
  }