// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
import androidx.compose.material.MaterialTheme
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries

val logger = LoggerFactory.getLogger("main")

fun log(level: Level, exception: Throwable? = null, lambda: () -> String) {
	if (logger.isEnabledForLevel(level)) {
		logger.atLevel(level).setCause(exception).log(lambda.invoke())
	}
}

sealed interface FileSystemEntry {
	val path: Path
	val size: Long
}

data class FSDirectory(
	override val path: Path, override val size: Long, val done: Boolean, val children: List<FileSystemEntry>
) : FileSystemEntry {
	fun updateOneStep(): FSDirectory {
		log(Level.DEBUG) { "Updating $path..." }
		if (done) {
			return this
		}
		if (children.isEmpty()) {
			log(Level.DEBUG) { "Populating children of $path..." }
			val newPathChildren: List<Path> = try {
				path.listDirectoryEntries()
			} catch (e: Exception) {
				when (e) {
					is java.nio.file.AccessDeniedException, is DirectoryIteratorException -> {
						log(Level.WARN, e) { "Could not get children for $path" }
						emptyList()
					}

					else -> {
						throw e
					}
				}
			}.filter { Files.isDirectory(it) || Files.isRegularFile(it) }
				.filter { !Files.isSymbolicLink(it) }
				.map { it.toRealPath() }
				.filter { it.startsWith(path) }

			val mappedChildren: List<FileSystemEntry> = newPathChildren.mapNotNull { pathToEntry(it) }
			val size = mappedChildren.sumOf { it.size }
			return if (mappedChildren.isEmpty()) {
				this.copy(done = true, size = size)
			} else {
				this.copy(children = mappedChildren, size = size)
			}
		}
		val notDoneDirectory = children.mapIndexed { index, fileSystemEntry ->
			Pair(index, fileSystemEntry)
		}.filter {
			it.second is FSDirectory
		}.map {
			Pair(it.first, it.second as FSDirectory)
		}.filter { !it.second.done }.shuffled().firstOrNull() ?: return this.copy(done = true)
		val newChildren = children.toMutableList()
		newChildren[notDoneDirectory.first] = notDoneDirectory.second.updateOneStep()
		val size = newChildren.sumOf { it.size }
		return this.copy(children = newChildren, size = size)
	}
}

data class FSFile(
	override val path: Path, override val size: Long
) : FileSystemEntry

data class FSRoots(
	val roots: List<FileSystemEntry>
) {
	fun split(possiblyStaleEntry: FSDirectory): FSRoots {
		val entry = roots
			.filterIsInstance<FSDirectory>()
			.first { it.path == possiblyStaleEntry.path }
		if (entry.children.isEmpty()) {
			return this
		}
		val newRoots = roots.toMutableList()
		val somethingRemoved = newRoots.remove(entry)
		if (!somethingRemoved) {
			return this
		}
		newRoots.addAll(entry.children)
		newRoots.sortByDescending { it.size }
		return this.copy(roots = newRoots)
	}

	fun updateOneStep(): FSRoots {
		val notDoneEntry = roots.mapIndexedNotNull { index, entry ->
			when (entry) {
				is FSFile -> null
				is FSDirectory -> {
					if (entry.done) {
						null
					} else {
						Pair(index, entry)
					}
				}
			}
		}.shuffled().firstOrNull() ?: return this
		val newRoots = roots.toMutableList()
		newRoots[notDoneEntry.first] = notDoneEntry.second.updateOneStep()
		return this.copy(roots = newRoots.sortedByDescending { it.size })
	}

	fun ignore(entry: FileSystemEntry): FSRoots {
		return this.copy(roots = roots.toMutableList().also { it.remove(entry) })
	}
}

fun pathToEntry(path: Path): FileSystemEntry? = when {
	Files.isDirectory(path) -> {
		FSDirectory(
			path = path, size = 0, done = false, children = emptyList()
		)
	}

	Files.isRegularFile(path) -> {
		FSFile(
			path = path, size = path.fileSize()
		)
	}

	else -> {
		//Ignoring "weird" file
		null
	}
}

fun sizeToString(size: Long): String {
	val minMultiple = 2
	val step = 1024
	var curUnitSize = size
	if (curUnitSize < step * minMultiple) {
		return "$curUnitSize bytes"
	}
	curUnitSize /= step
	if (curUnitSize < step * minMultiple) {
		return "$curUnitSize kilobytes"
	}
	curUnitSize /= step
	if (curUnitSize < step * minMultiple) {
		return "$curUnitSize megabytes"
	}
	curUnitSize /= step
	if (curUnitSize < step * minMultiple) {
		return "$curUnitSize gigabytes"
	}
	curUnitSize /= step
	return "$curUnitSize terabytes"
}

@Composable
fun cFileSystemEntry(entry: FileSystemEntry, ignoreCallback: () -> Unit, splitCallback: () -> Unit) {
	val hPad = 16.dp
	Row {
		Button(
			modifier = Modifier.padding(hPad, 0.dp),
			onClick = ignoreCallback
		) {
			Text("Ignore")
		}
		when (entry) {
			is FSDirectory -> {
				if (entry.children.isEmpty()) {
					//Do nothing
				} else {
					Button(onClick = splitCallback) {
						Text("Split")
					}
				}
			}

			is FSFile -> {
				//Do nothing
			}
		}
		Column(Modifier.padding(hPad, 0.dp)) {
			Text(
				text = entry.path.toString(), fontWeight = FontWeight.Bold
			)
			when (entry) {
				is FSDirectory -> {
					if (entry.done) {
						Text(
							text = "${entry.children.size} children, ${sizeToString(entry.size)}"
						)
					} else {
						Text(
							text = "${entry.children.size} children, ${sizeToString(entry.size)}..."
						)
					}
				}

				is FSFile -> {
					Text(
						text = sizeToString(entry.size)
					)
				}
			}
		}
	}
}

@Composable
@Preview
fun cApp(roots: FSRoots, ignoreCallback: (FileSystemEntry) -> Unit, splitCallback: (FileSystemEntry) -> Unit) {
	MaterialTheme {
		LazyColumn {
			for (root in roots.roots) {
				item(key = root.path) {
					cFileSystemEntry(root, ignoreCallback = { ignoreCallback(root) }, splitCallback = { splitCallback(root) })
				}
			}
		}
	}
}

fun main(args: Array<String>) {
	val roots: List<Path> = when (args.size) {
		0 -> {
			val curDirectory = Paths.get("").toRealPath()
			curDirectory.listDirectoryEntries()
		}

		1 -> {
			val preRoot = Paths.get(args[0]).toRealPath()
			preRoot.listDirectoryEntries()
		}

		else -> {
			args.map { Paths.get(it).toRealPath() }
		}
	}
	log(Level.INFO) { "Analyzing roots $roots ..." }

	application {
		val rootsLock = ReentrantLock()
		var composeRoots by remember {
			mutableStateOf(FSRoots(roots.mapNotNull { pathToEntry(it) }.sortedByDescending { it.size }))
		}
		Window(onCloseRequest = ::exitApplication) {
			cApp(composeRoots, ignoreCallback = {
				rootsLock.withLock {
					composeRoots = composeRoots.ignore(it)
				}
			}, splitCallback = {
				if (it is FSDirectory) {
					logger.info("START split; waiting on lock old size = ${composeRoots.roots.size}")
					rootsLock.withLock {
						logger.info("MIDDLE split; got lock, executing algo, old size = ${composeRoots.roots.size}")
						composeRoots = composeRoots.split(it)
						logger.info("MIDDLE split; done algo, releasing lock, new size = ${composeRoots.roots.size}")
					}
					logger.info("DONE split; new size = ${composeRoots.roots.size}")
				} else {
					//do nothing
				}
			})
		}

		LaunchedEffect(composeRoots) {
			launch(Dispatchers.IO) {
				while (true) {
					log(Level.DEBUG) { "new Update loop..." }
					//logger.info("START loop Waiting for rootsLock to loop")
					val beforeLock = System.currentTimeMillis()
					val beforeAlgo: Long
					rootsLock.withLock {
						beforeAlgo = System.currentTimeMillis()
						composeRoots = composeRoots.updateOneStep()
					}
					val after = System.currentTimeMillis()
					if (after - beforeLock > 1000) {
						logger.warn("Held roots lock for more than 1 second: waiting on lock: ${beforeAlgo - beforeLock}, running algo: ${after - beforeAlgo}, total: ${after - beforeLock}")
					}
					//logger.info("DONE loop Waiting for rootsLock to loop")
					yield()
				}
			}
		}
	}
}
