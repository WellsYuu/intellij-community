// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.workspace.jps.entities

import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityType
import com.intellij.platform.workspace.storage.GeneratedCodeApiVersion
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.platform.workspace.storage.annotations.Child
import com.intellij.platform.workspace.storage.impl.containers.toMutableWorkspaceList
import org.jetbrains.annotations.ApiStatus

/**
 * This entity stores order of facets in iml file. This is needed to ensure that facet tags are saved in the same order to avoid
 * unnecessary modifications of iml file.
 */
@ApiStatus.Internal
interface FacetsOrderEntity : WorkspaceEntity {
  val orderOfFacets: List<@NlsSafe String>
  val moduleEntity: ModuleEntity

  //region generated code
  @GeneratedCodeApiVersion(2)
  interface Builder : FacetsOrderEntity, WorkspaceEntity.Builder<FacetsOrderEntity> {
    override var entitySource: EntitySource
    override var orderOfFacets: MutableList<String>
    override var moduleEntity: ModuleEntity
  }

  companion object : EntityType<FacetsOrderEntity, Builder>() {
    @JvmOverloads
    @JvmStatic
    @JvmName("create")
    operator fun invoke(orderOfFacets: List<String>, entitySource: EntitySource, init: (Builder.() -> Unit)? = null): FacetsOrderEntity {
      val builder = builder()
      builder.orderOfFacets = orderOfFacets.toMutableWorkspaceList()
      builder.entitySource = entitySource
      init?.invoke(builder)
      return builder
    }
  }
  //endregion

}

//region generated code
fun MutableEntityStorage.modifyEntity(entity: FacetsOrderEntity, modification: FacetsOrderEntity.Builder.() -> Unit): FacetsOrderEntity = modifyEntity(FacetsOrderEntity.Builder::class.java, entity, modification)
//endregion

@get:ApiStatus.Internal
val ModuleEntity.facetOrder: @Child FacetsOrderEntity?
    by WorkspaceEntity.extension()

