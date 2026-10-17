package com.kimbar.observ.model

/**
 * All changes to a Test go through here, so that every change marks the Test as changed since
 * its last export (DESIGN.md 8) and every new moment registers its boot (DESIGN.md 3).
 */
object TestOps {

    private fun TestData.changed() = copy(changedSinceExport = true)

    fun registerBoot(t: TestData, m: Moment): TestData {
        if (t.bootBases.containsKey(m.boot)) return t
        return t.copy(
            bootBases = t.bootBases + (m.boot to (m.epochNs - m.counterNs)),
            firstBoot = t.firstBoot ?: m.boot,
        )
    }

    fun addItem(t: TestData, item: Item): TestData {
        var r = registerBoot(t, item.moment)
        item.end?.let { r = registerBoot(r, it) }
        return r.copy(items = r.items + item).changed()
    }

    fun updateItem(t: TestData, id: String, f: (Item) -> Item): TestData =
        t.copy(items = t.items.map { if (it.id == id) f(it) else it }).changed()

    fun setItemActive(t: TestData, id: String, active: Boolean) = updateItem(t, id) { it.copy(active = active) }

    fun setNoteText(t: TestData, id: String, text: String) = updateItem(t, id) { it.copy(text = text) }

    fun addSync(t: TestData, sync: Sync): TestData {
        val item = t.items.firstOrNull { it.id == sync.itemId }
        require(item != null && item.active && item.canSync) { "Item cannot be a sync source" }
        return t.copy(syncs = t.syncs + sync).changed()
    }

    fun updateSync(t: TestData, id: String, f: (Sync) -> Sync): TestData =
        t.copy(syncs = t.syncs.map { if (it.id == id) f(it) else it }).changed()

    fun setSyncActive(t: TestData, id: String, active: Boolean) = updateSync(t, id) { it.copy(active = active) }

    fun setReading(t: TestData, id: String, readingS: Int) = updateSync(t, id) { it.copy(readingS = readingS) }

    fun setTestActive(t: TestData, active: Boolean) = t.copy(active = active).changed()

    fun markExported(t: TestData) = t.copy(exportCount = t.exportCount + 1, changedSinceExport = false)

    fun canExport(t: TestData) = t.active

    fun canDelete(t: TestData) = !t.active || (t.exportCount > 0 && !t.changedSinceExport)

    /** `<name>.zip` the first time, then `<name>_v.2.zip`, `<name>_v.3.zip`, … */
    fun exportFileName(t: TestData): String {
        val base = safeFileName(t.name)
        val n = t.exportCount + 1
        return if (n == 1) "$base.zip" else "${base}_v.$n.zip"
    }

    /** Test names are unique ignoring case and surrounding spaces. */
    fun normalizeName(name: String) = name.trim().lowercase()

    fun safeFileName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_").ifEmpty { "test" }
}
