package com.hearthappy.loggerx.preview

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.transition.Slide
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayoutMediator
import com.hearthappy.basic.AbsBaseFragment
import com.hearthappy.basic.ext.dp
import com.hearthappy.basic.ext.popupWindow
import com.hearthappy.basic.ext.showAtBottom
import com.hearthappy.basic.ext.showAtCenter
import com.hearthappy.basic.tools.SlideDirection
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.R
import com.hearthappy.loggerx.core.LogScopeProxy
import com.hearthappy.loggerx.databinding.FragmentLoggerxPreviewBinding
import com.hearthappy.loggerx.databinding.PopLoggerxHintBinding
import com.hearthappy.loggerx.databinding.PopMultiFilterBinding
import com.hearthappy.loggerx.databinding.PopOperationChoiceBinding
import com.hearthappy.loggerx.image.LogImageLoaderFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PreviewLogFragment: AbsBaseFragment<FragmentLoggerxPreviewBinding>() {

    private val outPutterIndex: Int by lazy { arguments?.getInt("index") ?: 0 }
    private val scopeProxy: LogScopeProxy by lazy { LoggerX.getOutputters()[outPutterIndex].scope.getProxy() }
    private lateinit var logAdapter: LogAdapter
    private var popupWindow: PopupWindow? = null
    private val popupJobs = mutableListOf<Job>()
    private var lastClickTs: Long = 0L
    private var isConfirmed = false
    private val streamlineStateViewModel by activityViewModels<PreviewOperateViewModel>()

    override fun initViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoggerxPreviewBinding {
        return FragmentLoggerxPreviewBinding.inflate(inflater, container, false)
    }

    private val viewModel: PreviewViewModel by viewModels {
        PreviewViewModel.Factory(scopeProxy)
    }

    override fun FragmentLoggerxPreviewBinding.initData() {
    }

    override fun FragmentLoggerxPreviewBinding.initListener() {
        swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshAppliedLogs()
        }

        btnFilter.setOnClickListener {
            showFilterPopup()
        }
        btnDelete.setOnClickListener {
            showDeleteChoicePopup()
        }
        btnShared.setOnClickListener {
            showShareChoicePopup()
        }
    }

    override fun FragmentLoggerxPreviewBinding.initView(savedInstanceState: Bundle?) {
        btnDelete.setColorFilter(Color.WHITE)
        val context = context ?: return
        logAdapter = LogAdapter(context, outPutterIndex)
        rvLogList.layoutManager = LinearLayoutManager(requireContext())
        rvLogList.adapter = logAdapter
        rvLogList.setHasFixedSize(true)
        rvLogList.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    LogImageLoaderFactory.pauseDecode()
                } else {
                    LogImageLoaderFactory.resumeDecode()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) { // 向下滑动
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val pastVisibleItems = layoutManager.findFirstVisibleItemPosition()

                    if (!viewModel.logUiState.value.loading && viewModel.logUiState.value.hasMore) {
                        if ((visibleItemCount + pastVisibleItems) >= totalItemCount - 5) { // 提前5个项开始加载
                            viewModel.loadMoreLogs()
                        }
                    }
                }
            }
        })
        viewModel.loadInitialLogs()
    }

    override fun FragmentLoggerxPreviewBinding.initViewModelListener() {
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logUiState.collect { state ->
                        swipeRefreshLayout.isRefreshing = false
                        btnFilter.isEnabled = !state.loading
                        logAdapter.submitLogs(state.logs)
                    }
                }
                launch {
                    viewModel.toastEvent.collect {
                        Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamlineStateViewModel.logShowState.collect { state ->
                    logAdapter.isSimplified = state
                    logAdapter.notifyItemRangeChanged(0, logAdapter.itemCount)
                }
            }
        }
    }

    private fun showDeleteChoicePopup() {
        val popOperationBinding = PopOperationChoiceBinding.inflate(layoutInflater)
        popupWindow(popOperationBinding, width = 290.dp, height = ViewGroup.LayoutParams.WRAP_CONTENT, viewEventListener = { vb ->
            vb.apply {
                tvTitle.text = getString(R.string.delete_option)

                // 初始化：默认选择单个删除
                rbOptionSingle.isChecked = true

                tvConfirm.setOnClickListener {
                    if (rbOptionAll.isChecked) {
                        LoggerX.clear()
                        dismiss()
                        viewModel.refreshAppliedLogs()
                    } else {
                        dismiss()
                        showSingleDeleteConfirmation()
                    }
                }

                tvCancel.setOnClickListener { dismiss() }
            }
        }).showAtCenter(viewBinding.root)
    }

    private fun showShareChoicePopup() {
        val popOperationBinding = PopOperationChoiceBinding.inflate(layoutInflater)
        popupWindow(popOperationBinding, width = 290.dp, height = ViewGroup.LayoutParams.WRAP_CONTENT, viewEventListener = { vb ->
            vb.apply {
                tvTitle.text = getString(R.string.share_option)
                rbOptionAll.text = getString(R.string.share_all)
                rbOptionSingle.text = getString(R.string.share_single)

                // 初始化：默认选择全部分享
                rbOptionAll.isChecked = true

                tvConfirm.setOnClickListener {
                    if (rbOptionAll.isChecked) {
                        LoggerX.exportAndShareAll()
                    } else { // 分享单个日志的实现
                        LoggerX.getOutputters()[outPutterIndex].scope.doExportAndShare()
                    }
                    dismiss()
                }

                tvCancel.setOnClickListener { dismiss() }
            }
        }).showAtCenter(viewBinding.root)
    }

    private fun showSingleDeleteConfirmation() {
        val popHintBinding = PopLoggerxHintBinding.inflate(layoutInflater)
        popupWindow(popHintBinding, width = 290.dp, height = 160.dp, viewEventListener = { vb ->
            vb.apply {
                tvContent.text = getString(R.string.confirm_deletion)
                tvConfirm.setOnClickListener { // 删除单个日志的实现
                    LoggerX.getOutputters()[outPutterIndex].scope.delete()
                    viewModel.refreshAppliedLogs()
                    dismiss()
                }
                tvCancel.setOnClickListener { dismiss() }
            }
        }).showAtCenter(viewBinding.root)
    }


    private fun showFilterPopup() {
        isConfirmed = false
        viewModel.startFilterEditing()

        popupWindow?.dismiss()
        popupWindow = popupWindow(viewBinding = PopMultiFilterBinding.inflate(layoutInflater), width = ViewGroup.LayoutParams.MATCH_PARENT, height = ViewGroup.LayoutParams.WRAP_CONTENT, viewEventListener = { vb ->
            setupFilterPopup(vb)
            setOnDismissListener {
                popupJobs.forEach { it.cancel() }
                popupJobs.clear()
                if (!isConfirmed) {
                    viewModel.cancelDraft()
                }
                viewModel.clearDistinctCache()
            }
        }, transition = Slide(), slideDismiss = SlideDirection.VERTICAL)

        popupWindow?.showAtBottom(viewBinding.root)
    }

    private fun setupFilterPopup(vb: PopMultiFilterBinding) { // 构建过滤分类列表和数据
        val categories = FilterCategory.filterable
        var distinctValues: Map<FilterCategory, List<String>>
        var disabledCategories: Set<FilterCategory>
        var pagerAdapter: FilterPagerAdapter? = null


        // 设置 ViewPager2 和 TabLayout
        vb.viewPager.isUserInputEnabled = false
        vb.viewPager.offscreenPageLimit = 2  // 预加载相邻页面，优化性能
        //禁止viewpager切换滚动的动画

        vb.btnReset.setOnClickListener {
            if (!passDebounce()) return@setOnClickListener
            viewModel.resetDraft()
            popupWindow?.dismiss()
        }

        vb.btnConfirm.setOnClickListener {
            isConfirmed = true
            viewModel.confirmDraft()
            popupWindow?.dismiss()
        }

        // 订阅 distinctValues 更新
        popupJobs += viewLifecycleOwner.lifecycleScope.launch {
            viewModel.distinctValues.collect { state ->
                vb.pbDistinctLoading.isVisible = state.loading

                distinctValues = state.values
                disabledCategories = state.disabledCategories

                // 将字符串列表转换为 FilterChipItem 列表
                val itemsMap = distinctValues.mapValues { (category, values) ->
                    listOf(FilterChipItem.all(requireContext())) + values.map { value ->
                        val isSpecial = category == FilterCategory.LEVEL && (value == "ERROR" || value == "CRITICAL" || value == "FATAL")
                        FilterChipItem.fromString(value, isSpecial)
                    }
                }

                // 创建或更新 PagerAdapter
                if (pagerAdapter == null && distinctValues.isNotEmpty()) { // 只在有数据时创建 adapter
                    pagerAdapter = FilterPagerAdapter(requireActivity(), categories, itemsMap, viewModel.draftState.value, disabledCategories) { category, value ->
                        if (!passDebounce()) return@FilterPagerAdapter
                        viewModel.toggleSelection(category, value)
                    }
                    vb.viewPager.adapter = pagerAdapter

                    // 设置 TabLayout 和 ViewPager2 的关联
                    TabLayoutMediator(vb.tabLayout, vb.viewPager, true, false) { tab, position ->
                        tab.text = categories[position].title
                    }.attach()
                } else if (pagerAdapter != null && distinctValues.isNotEmpty()) { // 数据加载完成后，更新 adapter 中的数据
                    pagerAdapter.updateItems(itemsMap) // 同步选中状态
                    pagerAdapter.updateSelection(viewModel.draftState.value)
                }
            }
        }

        // 订阅 draftState 更新以同步选中状态
        popupJobs += viewLifecycleOwner.lifecycleScope.launch {
            viewModel.draftState.collect { draft ->
                pagerAdapter?.updateSelection(draft)
            }
        }
    }

    private fun passDebounce(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val pass = now - lastClickTs >= 250
        if (pass) lastClickTs = now
        return pass
    }

    override fun onDestroyView() {
        LogImageLoaderFactory.resumeDecode()
        super.onDestroyView()
    }

    companion object {
        fun newInstance(i: Int): PreviewLogFragment {
            return PreviewLogFragment().apply {
                arguments = Bundle().apply {
                    putInt("index", i)
                }
            }
        }
    }

}
