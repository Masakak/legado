package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.audio.HttpTtsAudioCache
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.service.HttpTtsPreCacheService
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private val mLayoutManager by lazy { UpLinearLayoutManager(requireContext()) }
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private var durChapterIndex = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.chapterListCallBack = this@ChapterListFragment
        val bbg = bottomBackground
        val btc = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        llChapterBaseInfo.setBackgroundColor(bbg)
        tvCurrentChapterInfo.setTextColor(btc)
        ivChapterTop.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(this@ChapterListFragment) {
            initBook(it)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(durChapterIndex, 0)
        }
        binding.llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        lifecycleScope.launch {
            upChapterList(null)
            durChapterIndex = book.durChapterIndex
            binding.tvCurrentChapterInfo.text =
                "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
            initCacheFileNames(book)
            initAudioCacheChapterIndexes(book)
        }
    }

    private fun initCacheFileNames(book: Book) {
        lifecycleScope.launch(IO) {
            adapter.cacheFileNames.addAll(BookHelp.getChapterFiles(book))
            withContext(Main) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
        observeEvent<Pair<String, Int>>(EventBus.HTTP_TTS_CACHE) { (bookUrl, chapterIndex) ->
            if (viewModel.bookData.value?.bookUrl == bookUrl) {
                adapter.audioCacheChapterIndexes.add(chapterIndex)
                notifyChapterAudioCacheChanged(chapterIndex)
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    if (viewModel.searchKey.isNullOrEmpty()) {
                        adapter.notifyItemChanged(chapter.index, true)
                    } else {
                        adapter.getItems().forEachIndexed { index, bookChapter ->
                            if (bookChapter.index == chapter.index) {
                                adapter.notifyItemChanged(index, true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun upChapterList(searchKey: String?) {
        lifecycleScope.launch {
            withContext(IO) {
                val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
                when {
                    searchKey.isNullOrBlank() ->
                        appDb.bookChapterDao.getChapterList(viewModel.bookUrl, 0, end)

                    else -> appDb.bookChapterDao.search(viewModel.bookUrl, searchKey, 0, end)
                }
            }.let {
                adapter.setItems(it)
            }
        }
    }

    override fun onListChanged() {
        lifecycleScope.launch {
            var scrollPos = 0
            withContext(Default) {
                adapter.getItems().forEachIndexed { index, bookChapter ->
                    if (bookChapter.index >= durChapterIndex) {
                        return@withContext
                    }
                    scrollPos = index
                }
            }
            mLayoutManager.scrollToPositionWithOffset(scrollPos, 0)
            adapter.upDisplayTitles(scrollPos)
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(mLayoutManager.findFirstVisibleItemPosition())
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

    override fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
            )
            finish()
        }
    }

    override fun onChapterLongClick(view: View, bookChapter: BookChapter): Boolean {
        val book = book ?: return true
        PopupMenu(requireContext(), view).apply {
            menu.add(0, 1, 0, "缓存本章 HTTP TTS")
            menu.add(0, 2, 1, "缓存后续 10 章 HTTP TTS")
            menu.add(0, 3, 2, "删除本章 HTTP TTS 缓存")
            menu.add(0, 4, 3, "清空全部 HTTP TTS 缓存")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        startHttpTtsCache(book, listOf(bookChapter.index))
                        true
                    }

                    2 -> {
                        val end = minOf(bookChapter.index + 9, book.lastChapterIndex)
                        startHttpTtsCache(book, (bookChapter.index..end).toList())
                        true
                    }

                    3 -> {
                        deleteHttpTtsCache(book, bookChapter.index)
                        true
                    }

                    4 -> {
                        deleteAllHttpTtsCache()
                        true
                    }

                    else -> false
                }
            }
        }.show()
        return true
    }

    private fun initAudioCacheChapterIndexes(book: Book) {
        val context = requireContext().applicationContext
        lifecycleScope.launch(IO) {
            val ttsUrl = getHttpTtsUrl(book)
            val indexes = if (ttsUrl == null) {
                emptySet()
            } else {
                HttpTtsAudioCache.getCachedChapterIndexes(
                    context,
                    book.bookUrl,
                    ttsUrl,
                    AppConfig.speechRatePlay + 5
                )
            }
            withContext(Main) {
                adapter.audioCacheChapterIndexes.clear()
                adapter.audioCacheChapterIndexes.addAll(indexes)
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    private fun startHttpTtsCache(book: Book, chapterIndexes: List<Int>) {
        val engine = book.getTtsEngine() ?: AppConfig.ttsEngine
        if (engine?.toLongOrNull() == null) {
            requireContext().toastOnUi("当前朗读引擎不是 HTTP TTS")
            return
        }
        HttpTtsPreCacheService.start(requireContext(), book.bookUrl, chapterIndexes)
        requireContext().toastOnUi("已加入 HTTP TTS 缓存队列")
    }

    private fun deleteHttpTtsCache(book: Book, chapterIndex: Int) {
        val context = requireContext().applicationContext
        lifecycleScope.launch(IO) {
            HttpTtsAudioCache.deleteChapter(context, book.bookUrl, chapterIndex)
            withContext(Main) {
                adapter.audioCacheChapterIndexes.remove(chapterIndex)
                notifyChapterAudioCacheChanged(chapterIndex)
                requireContext().toastOnUi("已删除本章 HTTP TTS 缓存")
            }
        }
    }

    private fun deleteAllHttpTtsCache() {
        alert("清空 HTTP TTS 缓存", "确定清空全部 HTTP TTS 音频缓存？") {
            yesButton {
                val context = requireContext().applicationContext
                lifecycleScope.launch(IO) {
                    HttpTtsAudioCache.deleteAll(context)
                    withContext(Main) {
                        adapter.audioCacheChapterIndexes.clear()
                        adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
                        requireContext().toastOnUi("已清空 HTTP TTS 缓存")
                    }
                }
            }
            noButton()
        }
    }

    private fun notifyChapterAudioCacheChanged(chapterIndex: Int) {
        if (viewModel.searchKey.isNullOrEmpty()) {
            adapter.notifyItemChanged(chapterIndex, true)
        } else {
            adapter.getItems().forEachIndexed { index, bookChapter ->
                if (bookChapter.index == chapterIndex) {
                    adapter.notifyItemChanged(index, true)
                }
            }
        }
    }

    private fun getHttpTtsUrl(book: Book): String? {
        val engine = book.getTtsEngine() ?: AppConfig.ttsEngine
        return engine?.toLongOrNull()?.let {
            appDb.httpTTSDao.get(it)?.url
        }
    }

}
