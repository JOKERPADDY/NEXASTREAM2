package com.nexastream.app.models

import com.nexastream.app.adapters.AppAdapter

class Category(
    var name: String,
    val list: List<AppAdapter.Item>,
) : AppAdapter.Item {

    var selectedIndex: Int = 0
    var itemSpacing: Int = 0

    private var _itemType: AppAdapter.Type? = null
    override var itemType: AppAdapter.Type
        get() = _itemType ?: AppAdapter.Type.CATEGORY_MOBILE_ITEM
        set(value) {
            _itemType = value
        }

    override var isSelected: Boolean = false

    fun copy(
        name: String = this.name,
        list: List<AppAdapter.Item> = this.list,
    ) = Category(
        name,
        list,
    ).also {
        _itemType?.let { type -> it.itemType = type }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Category

        if (name != other.name) return false
        if (list != other.list) return false
        if (selectedIndex != other.selectedIndex) return false
        if (itemSpacing != other.itemSpacing) return false
        if (isSelected != other.isSelected) return false
        return itemType == other.itemType
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + list.hashCode()
        result = 31 * result + selectedIndex
        result = 31 * result + itemSpacing
        result = 31 * result + isSelected.hashCode()
        result = 31 * result + itemType.hashCode()
        return result
    }

    companion object {
        const val FEATURED = ""
        const val CONTINUE_WATCHING = "Continue Watching"
        const val FAVORITE_MOVIES = "Favorite movies"
        const val FAVORITE_TV_SHOWS = "Favorite TV shows"
    }
}
