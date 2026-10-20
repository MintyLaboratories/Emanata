package com.infinity.comicreader.data

/**
 * How a favorite is shown: [name] in full color, [prefix] and [suffix] grayed,
 * e.g. `stacks/` + `Comics`, `Device…` + `Comics` or `Comics` + ` (2)`.
 */
data class FavoriteLabel(val prefix: String, val name: String, val suffix: String = "") {
    val text: String get() = prefix + name + suffix
}

/**
 * Tells favorites with the same name apart.
 *
 * - One instance keeps its bare name.
 * - When the clashing items sit on different storage roots, an item alone on its
 *   root is shown as `Root…Name`; items sharing a root fall through to the parent
 *   rules among themselves; a parent label that another root's items also produce
 *   gets its root in front, `Root…parent/Name`.
 * - Parent rules: the nearest ancestor no other clashing item has at the same
 *   depth. The parent gives `parent/Name`; a farther one gives `ancestor…Name`, or
 *   the folders spelled out (`ancestor/middle/Name`) when another clashing item
 *   has an ancestor of that name too, so `stacks…Comics` is not read as `stacks/Comics`.
 * - Labels that still collide become `Name`, `Name (2)`, `Name (3)`.
 *
 * A root itself is named by its root's name.
 */
object FavoriteLabels {
    const val SEPARATOR = "/"
    const val ELLIPSIS = "…"

    fun of(paths: List<String>, roots: List<StorageRoot>): Map<String, FavoriteLabel> {
        val items = paths.distinct().map { Item.of(it, roots) }
        val out = LinkedHashMap<String, FavoriteLabel>()
        for (group in items.groupBy { it.name.lowercase() }.values) {
            if (group.size == 1) {
                out[group[0].path] = FavoriteLabel("", group[0].name)
                continue
            }
            val labels = labelGroup(group)
            dedupe(group, labels)
            group.forEach { out[it.path] = labels.getValue(it) }
        }
        return out
    }

    /** The bare name a favorite shows when nothing clashes with it. */
    fun nameOf(path: String, roots: List<StorageRoot>): String = Item.of(path, roots).name

    private class Item(val path: String, val name: String, val root: StorageRoot?, val ancestors: List<String>) {
        /** The ancestor [depth] levels up (1 = parent), or null above the top. */
        fun at(depth: Int): String? = ancestors.getOrNull(ancestors.size - depth)

        companion object {
            fun of(path: String, roots: List<StorageRoot>): Item {
                val root = StorageRoot.of(path, roots)
                if (root != null && root.path == path) return Item(path, root.name, root, emptyList())
                val parts = if (root != null) root.segments(path) else Paths.parts(path)
                val name = parts.lastOrNull() ?: path
                return Item(path, name, root, parts.dropLast(1))
            }
        }
    }

    private fun labelGroup(group: List<Item>): MutableMap<Item, FavoriteLabel> {
        val labels = HashMap<Item, FavoriteLabel>()
        val byRoot = group.groupBy { it.root?.path }
        if (byRoot.size > 1) {
            for (members in byRoot.values) {
                val alone = members.singleOrNull()
                val root = alone?.root
                if (root != null) {
                    labels[alone] = FavoriteLabel(root.name + ELLIPSIS, alone.name)
                } else {
                    members.forEach { labels[it] = byParents(it, members) }
                }
            }
            prefixRepeatsWithRoot(group, labels)
        } else {
            group.forEach { labels[it] = byParents(it, group) }
        }
        return labels
    }

    /** A label that also turns up on another root gets its root in front: `Share A…stacks/Comics`. */
    private fun prefixRepeatsWithRoot(group: List<Item>, labels: MutableMap<Item, FavoriteLabel>) {
        val same = group.groupBy { labels.getValue(it).text.lowercase() }.values
        for (items in same) {
            if (items.mapTo(HashSet()) { it.root?.path }.size < 2) continue
            for (item in items) {
                val root = item.root ?: continue
                labels[item] = FavoriteLabel(root.name + ELLIPSIS + labels.getValue(item).prefix, item.name)
            }
        }
    }

    private fun byParents(item: Item, among: List<Item>): FavoriteLabel {
        val others = among.filter { it !== item }
        for (depth in 1..item.ancestors.size) {
            val ancestor = item.at(depth) ?: break
            if (others.any { it.at(depth).equals(ancestor, ignoreCase = true) }) continue
            val prefix = when {
                depth == 1 -> ancestor + SEPARATOR
                others.any { o -> o.ancestors.any { it.equals(ancestor, ignoreCase = true) } } ->
                    item.ancestors.takeLast(depth).joinToString(SEPARATOR) + SEPARATOR
                else -> ancestor + ELLIPSIS
            }
            return FavoriteLabel(prefix, item.name)
        }
        // Nothing tells it apart: show the parent, or the root for an item at the top.
        val parent = item.at(1) ?: item.root?.name
        return FavoriteLabel(parent?.let { it + SEPARATOR }.orEmpty(), item.name)
    }

    /** The first of each set of equal labels keeps the bare name; the others are numbered from 2. */
    private fun dedupe(group: List<Item>, labels: MutableMap<Item, FavoriteLabel>) {
        val clashes = group.groupBy { labels.getValue(it).text.lowercase() }.values.filter { it.size > 1 }
        for (same in clashes) {
            same.forEachIndexed { i, item ->
                labels[item] = FavoriteLabel("", item.name, if (i == 0) "" else " (${i + 1})")
            }
        }
    }
}
