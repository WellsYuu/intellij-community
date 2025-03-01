package org.jetbrains.plugins.notebooks.visualization

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.Processor
import com.intellij.util.SmartList
import com.intellij.util.asSafely
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.containers.SmartHashSet
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import org.jetbrains.annotations.TestOnly
import org.jetbrains.plugins.notebooks.ui.visualization.notebookAppearance
import org.jetbrains.plugins.notebooks.visualization.ui.EditorCell
import org.jetbrains.plugins.notebooks.ui.isFoldingEnabledKey
import java.awt.Graphics
import javax.swing.JComponent
import kotlin.math.max
import kotlin.math.min

class NotebookCellInlayManager private constructor(val editor: EditorImpl) : NotebookIntervalPointerFactory.ChangeListener {
  private val inlays: MutableMap<Inlay<*>, NotebookCellInlayController> = HashMap()
  private val notebookCellLines = NotebookCellLines.get(editor)
  private val viewportQueue = MergingUpdateQueue("NotebookCellInlayManager Viewport Update", 100, true, null, editor.disposable, null, true)

  /** 20 is 1000 / 50, two times faster than the eye refresh rate. Actually, the value has been chosen randomly, without experiments. */
  private val updateQueue = MergingUpdateQueue("NotebookCellInlayManager Interval Update", 20, true, null, editor.disposable, null, true)
  private var initialized = false

  private var _cells = mutableListOf<EditorCell>()

  val cells: List<EditorCell> get() = _cells.toList()

  /**
   * Listens for inlay changes (called after all inlays are updated). Feel free to convert it to the EP if you need another listener
   */
  var changedListener: InlaysChangedListener? = null

  fun inlaysForInterval(interval: NotebookCellLines.Interval): Iterable<NotebookCellInlayController> =
    _cells[interval.ordinal].controllers

  /** It's public, but think twice before using it. Called many times in a row, it can freeze UI. Consider using [update] instead. */
  fun updateImmediately(lines: IntRange) {
    if (initialized) {
      updateConsequentInlays(lines)
    }
  }

  /** It's public, but think seven times before using it. Called many times in a row, it can freeze UI. */
  fun updateAllImmediately() {
    if (initialized) {
      updateQueue.cancelAllUpdates()
      updateConsequentInlays(0..editor.document.lineCount)
    }
  }

  fun updateAll() {
    updateQueue.queue(UpdateInlaysTask(this, updateAll = true))
  }

  fun update(pointers: Collection<NotebookIntervalPointer>) {
    updateQueue.queue(UpdateInlaysTask(this, pointers = pointers))
  }

  fun update(pointer: NotebookIntervalPointer) {
    updateQueue.queue(UpdateInlaysTask(this, pointers = SmartList(pointer)))
  }

  private fun addViewportChangeListener() {
    editor.scrollPane.viewport.addChangeListener {
      scheduleUpdatePositions()
      viewportQueue.queue(object : Update("Viewport change") {
        override fun run() {
          if (editor.isDisposed) return
          for ((inlay, controller) in inlays) {
            controller.onViewportChange()

            // ToDo we should not call updateUI on any scroll event. This caused multiple calls to synchronizeBoundsWithInlay.
            // Many UI instances has overridden getPreferredSize relying on editor dimensions.
            inlay.renderer?.asSafely<JComponent>()?.updateUI()
          }
          _cells.forEach {
            it.onViewportChange()
          }
        }
      })
    }
  }

  private fun initialize() {
    // TODO It would be a cool approach to add inlays lazily while scrolling.

    editor.putUserData(key, this)

    handleRefreshedDocument()

    val connection = ApplicationManager.getApplication().messageBus.connect(editor.disposable)
    connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
      updateAll()
      refreshHighlightersLookAndFeel()
    })
    connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
      updateAll()
      refreshHighlightersLookAndFeel()
    })

    addViewportChangeListener()

    editor.foldingModel.addListener(object : FoldingListener {
      override fun onFoldProcessingEnd() {
        scheduleUpdatePositions()
      }
    }, editor.disposable)

    initialized = true
  }

  private fun scheduleUpdatePositions() {
    runInEdt {
      _cells.forEach { cell -> cell.updatePositions() }
    }
  }

  private fun refreshHighlightersLookAndFeel() {
    for (highlighter in editor.markupModel.allHighlighters) {
      if (highlighter.customRenderer === NotebookCellHighlighterRenderer) {
        (highlighter as? RangeHighlighterEx)?.setTextAttributes(textAttributesForHighlighter())
      }
    }
  }

  private fun handleRefreshedDocument() {
    ThreadingAssertions.softAssertReadAccess()
    _cells.forEach {
      it.dispose()
    }
    val pointerFactory = NotebookIntervalPointerFactory.get(editor)
    _cells = notebookCellLines.intervals.map { interval ->
      createCell(pointerFactory.create(interval))
    }.toMutableList()
    addHighlighters(notebookCellLines.intervals)
    inlaysChanged()
  }

  private fun createCell(interval: NotebookIntervalPointer) = EditorCell(
    editor,
    notebookCellLines,
    interval
  )

  private fun ensureInlaysAndHighlightersExist(matchingCellsBeforeChange: List<NotebookCellLines.Interval>, logicalLines: IntRange) {
    val interestingRange =
      matchingCellsBeforeChange
        .map { it.lines }
        .takeIf { it.isNotEmpty() }
        ?.let { min(logicalLines.first, it.first().first)..max(it.last().last, logicalLines.last) }
      ?: logicalLines
    updateConsequentInlays(interestingRange)
  }

  private fun inlaysChanged() {
    changedListener?.inlaysChanged()
  }

  private fun updateConsequentInlays(interestingRange: IntRange) {
    ThreadingAssertions.softAssertReadAccess()
    editor.notebookCellEditorScrollingPositionKeeper?.saveSelectedCellPosition()
    val matchingIntervals = notebookCellLines.getMatchingCells(interestingRange)
    val fullInterestingRange =
      if (matchingIntervals.isNotEmpty()) matchingIntervals.first().lines.first..matchingIntervals.last().lines.last
      else interestingRange

    val existingHighlighters = getMatchingHighlightersForLines(fullInterestingRange)
    val intervalsToAddHighlightersFor = matchingIntervals.associateByTo(HashMap()) { it.lines }
    for (highlighter in existingHighlighters) {
      val lines = editor.document.run { getLineNumber(highlighter.startOffset)..getLineNumber(highlighter.endOffset) }
      if (intervalsToAddHighlightersFor.remove(lines)?.shouldHaveHighlighter != true) {
        editor.markupModel.removeHighlighter(highlighter)
      }
    }
    addHighlighters(intervalsToAddHighlightersFor.values)

    for (interval in matchingIntervals) {
      _cells[interval.ordinal].update()
    }

    NotebookGutterLineMarkerManager().putHighlighters(editor)

    inlaysChanged()
  }

  private fun getMatchingHighlightersForLines(lines: IntRange): List<RangeHighlighterEx> =
    mutableListOf<RangeHighlighterEx>()
      .also { list ->
        val startOffset = editor.document.getLineStartOffset(saturateLine(lines.first))
        val endOffset = editor.document.getLineEndOffset(saturateLine(lines.last))
        editor.markupModel.processRangeHighlightersOverlappingWith(startOffset, endOffset, Processor {
          if (it.customRenderer === NotebookCellHighlighterRenderer) {
            list.add(it)
          }
          true
        })
      }

  private fun saturateLine(line: Int): Int =
    line.coerceAtMost(editor.document.lineCount - 1).coerceAtLeast(0)

  private val NotebookCellLines.Interval.shouldHaveHighlighter: Boolean
    get() = type == NotebookCellLines.CellType.CODE

  private fun addHighlighters(intervals: Collection<NotebookCellLines.Interval>) {
    val document = editor.document
    for (interval in intervals) {
      if (interval.shouldHaveHighlighter) {
        val highlighter = editor.markupModel.addRangeHighlighter(
          document.getLineStartOffset(interval.lines.first),
          document.getLineEndOffset(interval.lines.last),
          // Code cell background should be seen behind any syntax highlighting, selection or any other effect.
          HighlighterLayer.FIRST - 100,
          textAttributesForHighlighter(),
          HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighter.customRenderer = NotebookCellHighlighterRenderer
      }
    }
  }

  private fun textAttributesForHighlighter() = TextAttributes().apply {
    backgroundColor = editor.notebookAppearance.getCodeCellBackground(editor.colorsScheme)
  }

  private fun NotebookCellLines.getMatchingCells(logicalLines: IntRange): List<NotebookCellLines.Interval> =
    mutableListOf<NotebookCellLines.Interval>().also { result ->
      // Since inlay appearance may depend from neighbour cells, adding one more cell at the start and at the end.
      val iterator = intervalsIterator(logicalLines.first)
      if (iterator.hasPrevious()) iterator.previous()
      for (interval in iterator) {
        result.add(interval)
        if (interval.lines.first > logicalLines.last) break
      }
    }

  @TestOnly
  fun getInlays(): MutableMap<Inlay<*>, NotebookCellInlayController> = inlays

  @TestOnly
  fun updateControllers(matchingCells: List<NotebookCellLines.Interval>, logicalLines: IntRange) {
    ensureInlaysAndHighlightersExist(matchingCells, logicalLines)
  }

  companion object {
    @JvmStatic
    fun install(editor: EditorImpl) {
      val notebookCellInlayManager = NotebookCellInlayManager(editor)
      editor.putUserData(isFoldingEnabledKey, Registry.`is`("jupyter.editor.folding.cells"))
      NotebookIntervalPointerFactory.get(editor).changeListeners.addListener(notebookCellInlayManager, editor.disposable)
      notebookCellInlayManager.initialize()
    }

    @JvmStatic
    fun get(editor: Editor): NotebookCellInlayManager? = key.get(editor)

    private val key = Key.create<NotebookCellInlayManager>(NotebookCellInlayManager::class.java.name)
  }

  override fun onUpdated(event: NotebookIntervalPointersEvent) {
    var start = Int.MAX_VALUE
    var end = Int.MIN_VALUE
    for (change in event.changes) {
      when (change) {
        is NotebookIntervalPointersEvent.OnEdited -> {
          start = minOf(start, change.intervalBefore.lines.first, change.intervalAfter.lines.first)
          end = maxOf(end, change.intervalBefore.lines.last, change.intervalAfter.lines.last)
        }
        is NotebookIntervalPointersEvent.OnInserted -> {
          change.subsequentPointers.forEach {
            _cells.add(it.interval.ordinal, createCell(it.pointer))
          }
          start = minOf(start, change.subsequentPointers.first().interval.lines.first)
          end = maxOf(end, change.subsequentPointers.last().interval.lines.last)
          scheduleUpdatePositions()
        }
        is NotebookIntervalPointersEvent.OnRemoved -> {
          change.subsequentPointers.reversed().forEach {
            val removed = _cells.removeAt(it.interval.ordinal)
            removed.dispose()
          }
          start = minOf(start, change.subsequentPointers.first().interval.lines.first)
          end = maxOf(end, change.subsequentPointers.last().interval.lines.last)
          scheduleUpdatePositions()
        }
        is NotebookIntervalPointersEvent.OnSwapped -> {
          val first = _cells[change.firstOrdinal].intervalPointer
          _cells[change.firstOrdinal].intervalPointer = _cells[change.secondOrdinal].intervalPointer
          _cells[change.secondOrdinal].intervalPointer = first
          start = minOf(start, change.first.interval.lines.first)
          end = maxOf(end, change.second.interval.lines.last)
        }
      }
    }
    updateConsequentInlays(start..end)
  }
}

/**
 * Renders rectangle in the right part of editor to make filled code cells look like rectangles with margins.
 * But mostly it's used as a token to filter notebook cell highlighters.
 */
private object NotebookCellHighlighterRenderer : CustomHighlighterRenderer {
  override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
    editor as EditorImpl
    @Suppress("NAME_SHADOWING") g.create().use { g ->
      val scrollbarWidth = editor.scrollPane.verticalScrollBar.width
      val oldBounds = g.clipBounds
      val visibleArea = editor.scrollingModel.visibleArea
      g.setClip(
        visibleArea.x + visibleArea.width - scrollbarWidth,
        oldBounds.y,
        scrollbarWidth,
        oldBounds.height
      )

      g.color = editor.colorsScheme.defaultBackground
      g.clipBounds.run {
        val fillX = if (editor.editorKind == EditorKind.DIFF && editor.isMirrored) x + 20 else x
        g.fillRect(fillX, y, width, height)
      }
    }
  }
}

private class UpdateInlaysTask(private val manager: NotebookCellInlayManager,
                               pointers: Collection<NotebookIntervalPointer>? = null,
                               private var updateAll: Boolean = false) : Update(Any()) {
  private val pointerSet = pointers?.let { SmartHashSet(pointers) } ?: SmartHashSet()

  override fun run() {
    if (updateAll) {
      manager.updateAllImmediately()
      return
    }

    val linesList = pointerSet.mapNotNullTo(mutableListOf()) { it.get()?.lines }
    linesList.sortBy { it.first }
    linesList.mergeAndJoinIntersections(listOf())

    for (lines in linesList) {
      manager.updateImmediately(lines)
    }
  }

  override fun canEat(update: Update): Boolean {
    update as UpdateInlaysTask

    updateAll = updateAll || update.updateAll
    if (updateAll) {
      return true
    }

    pointerSet.addAll(update.pointerSet)
    return true
  }
                               }