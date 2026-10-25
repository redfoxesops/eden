// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.InputStream
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.fragments.MessageDialogFragment
import org.yuzu.yuzu_emu.fragments.ProgressDialogFragment
import org.yuzu.yuzu_emu.model.DirectoryType
import org.yuzu.yuzu_emu.model.DriverViewModel
import org.yuzu.yuzu_emu.model.GameDir
import org.yuzu.yuzu_emu.model.GamesViewModel

/**
 * Copies user data from an installed official Eden into this app. Eden's user directory is read
 * through its exported DocumentProvider, which the user grants with one folder pick. Game folders
 * and external content folders are path only, so they are re-granted one by one instead.
 */
object EdenImport {
    private val PACKAGES = listOf(
        "dev.eden.eden_emulator" to R.string.import_from_eden_source_standard,
        "dev.eden.eden_emulator.nightly" to R.string.import_from_eden_source_nightly,
        "dev.legacy.eden_emulator" to R.string.import_from_eden_source_legacy
    )

    enum class Item(@StringRes val titleId: Int, val defaultChecked: Boolean) {
        KEYS(R.string.keys, true),
        FIRMWARE(R.string.firmware, true),
        SAVES(R.string.save_data, true),
        MODS(R.string.import_from_eden_mods, true),
        CONFIG(R.string.import_from_eden_config, true),
        GAME_FOLDERS(R.string.import_from_eden_game_folders, true),
        EXTERNAL_CONTENT(R.string.import_from_eden_external_content, true),
        INSTALLED_CONTENT(R.string.import_from_eden_installed_content, false),
        GPU_DRIVERS(R.string.import_from_eden_gpu_drivers, false)
    }

    fun installedSources(context: Context): List<Pair<String, Int>> =
        PACKAGES.filter {
            try {
                context.packageManager.getPackageInfo(it.first, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    /** Location of a directory inside Eden: relative to the granted tree, or a raw custom path. */
    private class Source(val treePath: String?, val file: File?)

    private class EdenPaths(
        val nand: Source,
        val save: Source,
        val gameDirs: List<GameDir>,
        val externalContentDirs: List<GameDir>
    )

    private class Entry(val size: Long, val dest: File, val open: () -> InputStream)

    private const val PATHS = "Paths\\"
    private val DROPPED_KEYS = listOf(
        "gamedirs\\",
        "external_content_dirs\\",
        "nand_directory",
        "sdmc_directory",
        "save_directory"
    ).map { PATHS + it }

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"")

    private fun readPaths(config: String?, pkg: String): EdenPaths {
        val values = config?.lineSequence()
            ?.filter { it.startsWith(PATHS) && it.contains('=') }
            ?.associate { it.substringBefore('=') to unquote(it.substringAfter('=')) }
            ?: emptyMap()

        val edenFiles = "/Android/data/$pkg/files/"
        fun source(key: String, fallback: Source): Source {
            val path = values[PATHS + key]?.trimEnd('/')
            if (path.isNullOrEmpty()) return fallback
            if (path.contains(edenFiles)) return Source(path.substringAfter(edenFiles), null)
            return Source(null, File(path))
        }

        fun dirs(name: String, type: DirectoryType): List<GameDir> {
            val size = values["$PATHS$name\\size"]?.toIntOrNull() ?: 0
            return (1..size).mapNotNull { i ->
                val path = values["$PATHS$name\\$i\\path"]
                if (path.isNullOrEmpty()) return@mapNotNull null
                val deepScan = values["$PATHS$name\\$i\\deep_scan"] == "true"
                GameDir(path, deepScan, type)
            }
        }

        val nand = source("nand_directory", Source("nand", null))
        return EdenPaths(
            nand = nand,
            save = source("save_directory", nand),
            gameDirs = dirs("gamedirs", DirectoryType.GAME),
            externalContentDirs = dirs("external_content_dirs", DirectoryType.EXTERNAL_CONTENT)
        )
    }

    private class Child(val name: String, val uri: Uri, val isDirectory: Boolean, val size: Long)

    private fun listChildren(context: Context, tree: Uri, doc: Uri): List<Child> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(doc)
        )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val result = mutableListOf<Child>()
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result.add(
                    Child(
                        name = c.getString(1),
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (c.isNull(3)) 0 else c.getLong(3)
                    )
                )
            }
        }
        return result
    }

    private fun findDoc(context: Context, tree: Uri, path: String): Uri? {
        var doc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        for (name in path.split('/').filter { it.isNotEmpty() }) {
            doc = listChildren(context, tree, doc).firstOrNull { it.name == name }?.uri
                ?: return null
        }
        return doc
    }

    private fun collect(context: Context, tree: Uri, source: Source, dest: File): List<Entry> {
        source.file?.let { root ->
            return root.walkTopDown().filter { it.isFile }.map { file ->
                Entry(file.length(), File(dest, file.relativeTo(root).path)) { file.inputStream() }
            }.toList()
        }

        val result = mutableListOf<Entry>()
        fun walk(doc: Uri, dir: File) {
            for (child in listChildren(context, tree, doc)) {
                val target = File(dir, child.name)
                if (child.isDirectory) {
                    walk(child.uri, target)
                } else {
                    result.add(Entry(child.size, target) {
                        context.contentResolver.openInputStream(child.uri)!!
                    })
                }
            }
        }
        findDoc(context, tree, source.treePath!!)?.let { walk(it, dest) }
        return result
    }

    private fun Source.child(path: String): Source =
        Source(treePath?.let { "$it/$path" }, file?.let { File(it, path) })

    private fun readText(context: Context, tree: Uri, path: String): String? =
        findDoc(context, tree, path)?.let { doc ->
            context.contentResolver.openInputStream(doc)?.use { it.bufferedReader().readText() }
        }

    private fun hasAllFilesPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * UI flow, created as a property of the hosting fragment so the result launchers are
     * registered before the fragment is created.
     */
    class Importer(
        private val fragment: Fragment,
        private val warnOverwrite: Boolean,
        private val onFinished: () -> Unit = {}
    ) {
        private val context get() = fragment.requireContext()

        private var pkg = ""
        private var sourceName = ""
        private var items = emptySet<Item>()
        private var tree: Uri? = null
        private val pendingFolders = ArrayDeque<GameDir>()
        private var succeeded = false

        private val treeLauncher =
            fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                if (it != null) {
                    tree = it
                    checkPermissionAndCopy()
                }
            }

        private val allFilesLauncher =
            fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (hasAllFilesPermission()) {
                    copy()
                } else {
                    Toast.makeText(
                        context,
                        R.string.all_files_permission_required,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        private val folderLauncher =
            fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                val dir = pendingFolders.removeFirstOrNull() ?: return@registerForActivityResult
                if (it != null) {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val gamesViewModel =
                        ViewModelProvider(fragment.requireActivity())[GamesViewModel::class.java]
                    val uriString = it.toString()
                    if (gamesViewModel.folders.value.none { f -> f.uriString == uriString }) {
                        gamesViewModel.addFolder(
                            GameDir(uriString, dir.deepScan, dir.type),
                            savedFromGameFragment = false
                        )
                    }
                }
                grantNextFolder()
            }

        fun start() {
            val sources = installedSources(context)
            when (sources.size) {
                0 -> return
                1 -> chooseItems(sources[0])
                else -> MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.import_from_eden_choose_source)
                    .setItems(sources.map { context.getString(it.second) }.toTypedArray()) { _, i ->
                        chooseItems(sources[i])
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        private fun chooseItems(source: Pair<String, Int>) {
            pkg = source.first
            // Name shown in the picker's side menu (DocumentProvider root title = Eden's app name)
            sourceName = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                context.getString(source.second)
            }
            val all = Item.values().toList()
            val checked = all.map { it.defaultChecked }.toBooleanArray()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.import_from_eden)
                .setMultiChoiceItems(
                    all.map { context.getString(it.titleId) }.toTypedArray(),
                    checked
                ) { _, i, isChecked -> checked[i] = isChecked }
                .setPositiveButton(R.string.import_from_eden_start) { _, _ ->
                    items = all.filterIndexed { i, _ -> checked[i] }.toSet()
                    if (items.isEmpty()) return@setPositiveButton
                    showSteps()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun showSteps() {
            val needsFolders = Item.GAME_FOLDERS in items || Item.EXTERNAL_CONTENT in items
            var steps = context.getString(R.string.import_from_eden_steps, sourceName)
            if (needsFolders) {
                steps += "\n\n" + context.getString(R.string.import_from_eden_steps_folders)
            }
            val message = buildSpannedString {
                if (warnOverwrite) {
                    bold { append(context.getString(R.string.import_from_eden_overwrite_warning)) }
                    append("\n\n")
                }
                append(steps)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.import_from_eden_steps_title)
                .setMessage(message)
                .setPositiveButton(R.string.import_from_eden_continue) { _, _ ->
                    treeLauncher.launch(DocumentsContract.buildDocumentUri("$pkg.user", "root"))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun edenPaths(): EdenPaths =
            readPaths(readText(context, tree!!, "config/config.ini"), pkg)

        private fun checkPermissionAndCopy() {
            val paths = edenPaths()
            val needsFiles = (paths.nand.file != null && (Item.FIRMWARE in items ||
                Item.INSTALLED_CONTENT in items)) ||
                (paths.save.file != null && Item.SAVES in items)
            if (!needsFiles || hasAllFilesPermission()) {
                copy()
                return
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.all_files_permission_required)
                .setMessage(R.string.import_from_eden_all_files_description)
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    val intent =
                        Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:${context.packageName}".toUri()
                    allFilesLauncher.launch(intent)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun copy() {
            succeeded = false
            pendingFolders.clear()
            val activity = fragment.requireActivity()
            val tree = tree!!
            val pkg = pkg
            val items = items
            val userDir = File(DirectoryInitialization.userDirectory!!)
            val nandDir = File(NativeConfig.getNandDir())
            val saveDir = File(NativeConfig.getSaveDir())

            ProgressDialogFragment.newInstance(
                activity,
                R.string.import_from_eden_importing,
                true
            ) { progressCallback, messageCallback ->
                val paths = edenPaths()
                val root = Source("", null)
                val sources = mutableListOf<Pair<Source, File>>()
                if (Item.KEYS in items) sources.add(root.child("keys") to File(userDir, "keys"))
                if (Item.FIRMWARE in items) {
                    sources.add(paths.nand.child("system") to File(nandDir, "system"))
                }
                if (Item.SAVES in items) {
                    sources.add(paths.save.child("user/save") to File(saveDir, "user/save"))
                }
                if (Item.MODS in items) sources.add(root.child("load") to File(userDir, "load"))
                if (Item.INSTALLED_CONTENT in items) {
                    sources.add(
                        paths.nand.child("user/Contents") to File(nandDir, "user/Contents")
                    )
                }
                if (Item.GPU_DRIVERS in items) {
                    sources.add(root.child("gpu_drivers") to File(userDir, "gpu_drivers"))
                }

                messageCallback(activity.getString(R.string.import_from_eden_scanning))
                val entries = sources.flatMap { (source, dest) ->
                    collect(activity, tree, source, dest)
                }
                val config = if (Item.CONFIG in items) {
                    collect(activity, tree, root.child("config"), File(userDir, "config"))
                } else {
                    emptyList()
                }

                val total = (entries + config).sumOf { it.size }
                if (total > userDir.freeSpace) {
                    return@newInstance MessageDialogFragment.newInstance(
                        activity,
                        titleId = R.string.import_failed,
                        descriptionId = R.string.import_from_eden_no_space
                    )
                }

                messageCallback("")
                var done = 0L
                fun write(entry: Entry, transform: ((String) -> String)? = null): Boolean {
                    entry.dest.parentFile?.mkdirs()
                    entry.open().use { input ->
                        if (transform == null) {
                            entry.dest.outputStream().use { input.copyTo(it) }
                        } else {
                            entry.dest.writeText(transform(input.bufferedReader().readText()))
                        }
                    }
                    done += entry.size
                    return progressCallback(total, done)
                }

                try {
                    for (entry in entries) {
                        if (write(entry)) return@newInstance Unit
                    }

                    if (config.isNotEmpty()) {
                        val mainConfig = File(userDir, "config/config.ini")
                        NativeConfig.unloadGlobalConfig()
                        for (entry in config) {
                            // Eden's own paths and folder grants are useless here: re-point
                            // absolute paths to this app and drop the folder lists
                            val transform = if (entry.dest == mainConfig) { text: String ->
                                text.replace(
                                    "/Android/data/$pkg/files",
                                    "/Android/data/${activity.packageName}/files"
                                ).lineSequence()
                                    .filterNot { line -> DROPPED_KEYS.any { line.startsWith(it) } }
                                    .joinToString("\n")
                            } else {
                                null
                            }
                            if (write(entry, transform)) break
                        }
                    }
                } catch (e: Exception) {
                    Log.error("[EdenImport] Import failed: ${e.message}")
                    return@newInstance MessageDialogFragment.newInstance(
                        activity,
                        titleId = R.string.import_failed,
                        descriptionString = e.message ?: ""
                    )
                } finally {
                    NativeLibrary.initializeSystem(true)
                    if (config.isNotEmpty()) NativeConfig.initializeGlobalConfig()
                    ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(false)
                    ViewModelProvider(activity)[DriverViewModel::class.java].reloadDriverData()
                }

                succeeded = true
                if (Item.GAME_FOLDERS in items) pendingFolders.addAll(paths.gameDirs)
                if (Item.EXTERNAL_CONTENT in items) pendingFolders.addAll(paths.externalContentDirs)
                activity.getString(R.string.import_from_eden_success)
            }.apply {
                onDialogComplete = { grantNextFolder() }
            }.show(fragment.parentFragmentManager, ProgressDialogFragment.TAG)
        }

        private fun grantNextFolder() {
            val dir = pendingFolders.firstOrNull()
            if (dir == null) {
                if (succeeded) onFinished()
                return
            }
            val label = context.getString(
                if (dir.type == DirectoryType.GAME) R.string.games else R.string.external_content
            )
            val uri = dir.uriString.toUri()
            val folder = Uri.decode(DocumentsContract.getTreeDocumentId(uri)).substringAfter(':')
            Toast.makeText(
                context,
                context.getString(R.string.import_from_eden_pick_game_folder, label, folder),
                Toast.LENGTH_LONG
            ).show()
            folderLauncher.launch(
                DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri)
                )
            )
        }
    }
}
