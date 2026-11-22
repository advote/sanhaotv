package com.github.tvbox.osc.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.BounceInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private TvRecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private boolean isRequesting = false;
    private boolean hasActionItems = false;
    private boolean isTop = true;
    private View focusedView = null;
    private float pullRefreshStartX;
    private float pullRefreshStartY;
    private boolean pullRefreshStartAtTop = false;
    private boolean pullRefreshReady = false;
    private int pullRefreshThreshold = 0;
    private View loadSirView = null;

    private static class GridInfo{
        public String sortID="";
        public TvRecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public boolean hasActionItems = false;
        public View focusedView= null;
    }
    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TvRecyclerView gridView = view.findViewById(R.id.mGridView);
        if (gridView != null && gridView.getLayoutManager() == null) {
            gridView.setLayoutManager(new V7LinearLayoutManager(mContext, 1, false));
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    private void changeView(String id,Boolean isFolder){
        if(isFolder){
            this.sortData.flag =style==null?"1":"2"; // 修改sortData.flag
        }else {
            this.sortData.flag ="2"; // 修改sortData.flag
        }
        initView();
        this.sortData.id =id; // 修改sortData.id为新的ID
        initViewModel();
        initData();
    }
    public boolean isFolederMode(){ return (getUITag() =='1'); }
    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag(){
        return (sortData == null || sortData.flag == null || sortData.flag.length() ==0 || style!=null) ?  '0' : sortData.flag.charAt(0);
    }
    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch(){  return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }
    // 保存当前页面
    private void saveCurrentView(){
        if(this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.hasActionItems = this.hasActionItems;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }
    // 丢弃当前页面，将页面还原成上一个保存的页面
    public boolean restoreView(){
        if(mGrids.empty()) return false;
        this.showSuccess();
        ((ViewGroup) mGridView.getParent()).removeView(this.mGridView); // 重父窗口移除当前控件
        GridInfo info = mGrids.pop();// 还原上次保存的控件
        this.sortData.id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.hasActionItems = info.hasActionItems;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
//        if(this.focusedView != null){ this.focusedView.requestFocus(); }
        if(mGridView != null) mGridView.requestFocus();
        return true;
    }

    private ImgUtil.Style style;
    // 更改当前页面
    private void createView() {
        this.saveCurrentView(); // 保存当前页面
        if(mGridView == null){ // 从layout中拿view
            mGridView = findViewById(R.id.mGridView);
        }else{ // 复制当前view
            TvRecyclerView v3 = new TvRecyclerView(this.mContext);
            v3.setSpacingWithMargins(10,10);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            ((ViewGroup) mGridView.getParent()).addView(v3);
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        style=ImgUtil.initStyle();
        gridAdapter = new GridAdapter(isFolederMode(), style);
        this.page =1;
        this.maxPage =1;
        this.isLoad = false;
    }

    private void initView() {
        this.createView();
        if(isFolederMode()){
            mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        }else{
            int spanCount = isBaseOnWidth() ? 5 : 6;
            if (style != null) {
                spanCount = ImgUtil.spanCountByStyle(style, spanCount);
            }
            if (spanCount == 1) {
                mGridView.setLayoutManager(new V7LinearLayoutManager(mContext, spanCount, false));
            } else {
                mGridView.setLayoutManager(new V7GridLayoutManager(mContext, spanCount));
            }
        }
        mGridView.setAdapter(gridAdapter);

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                gridAdapter.setEnableLoadMore(true);
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                }
                return false;
            }
        });
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    // 三好TV：点击“未扫码”授权卡片后，启动静默轮询重新请求当前分类，
                    // 直接用 spider 返回的真实状态刷新文案（与“退出再进”走同一路径）。
                    // 触发放在最前面，即使该卡片没有 action 也能生效。
                    if (video.note != null && video.note.contains("未扫码")) {
                        startSilentAuthReload();
                    }
                    if (video.action != null) {
                        sourceViewModel.action(video.sourceKey, video.action);
                        return;
                    }
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if( video.tag !=null && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        if(("12".indexOf(getUITag()) != -1)){
                            changeView(video.id,video.tag.equals("folder"));
                        }else {
                            changeView(video.id,false);
                        }
                    }
                    else{
                        if (video.id != null && video.id.startsWith("msearch:")) {
                            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true) && enableFastSearch()) {
                                jumpActivity(FastSearchActivity.class, bundle);
                            } else {
                                jumpActivity(SearchActivity.class, bundle);
                            }
                        } else {
                            bundle.putString("picture", video.pic);
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }

                }
            }
        });
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if (video.id != null && video.id.startsWith("msearch:")) {
                        bundle.putString("picture", video.pic);
                        jumpActivity(DetailActivity.class, bundle);
                    } else {
                        jumpActivity(FastSearchActivity.class, bundle);
                    }
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());
        setLoadSir2(mGridView);
        initPullRefresh();
    }

    private void initPullRefresh() {
        pullRefreshThreshold = ViewConfiguration.get(mContext).getScaledTouchSlop() * 6;
        mGridView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                return handlePullRefreshTouch(rv, event);
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            }
        });
        loadSirView = (View) mGridView.getParent();
        if (loadSirView != null) {
            loadSirView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return handlePullRefreshTouch(v, event);
                }
            });
        }
    }

    private void bindPullRefreshTouch(View view) {
        if (view == null || view == mGridView) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handlePullRefreshTouch(v, event);
            }
        });
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindPullRefreshTouch(group.getChildAt(i));
            }
        }
    }

    private boolean handlePullRefreshTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pullRefreshStartX = event.getX();
                pullRefreshStartY = event.getY();
                pullRefreshStartAtTop = !view.canScrollVertically(-1);
                pullRefreshReady = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float diffX = Math.abs(event.getX() - pullRefreshStartX);
                float diffY = event.getY() - pullRefreshStartY;
                pullRefreshReady = pullRefreshStartAtTop && diffY > pullRefreshThreshold && diffY > diffX;
                break;
            case MotionEvent.ACTION_UP:
                if (pullRefreshReady) {
                    pullRefreshReady = false;
                    forceRefresh();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                pullRefreshReady = false;
                break;
        }
        return false;
    }

    @Override
    protected void showEmpty() {
        super.showEmpty();
        bindPullRefreshTouch(loadSirView);
    }

    private void initViewModel() {
        if(sourceViewModel != null) { return;}
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                isRequesting = false;
                // 三好TV：扫码后的静默重拉回包 —— 只更新授权卡片的真实状态文案，
                // 不 showLoading、不 scrollTop、不动 adapter 数据集与焦点。
                if (silentAuthReloading) {
                    silentAuthReloading = false;
                    handleSilentAuthResult(absXml);
                    return;
                }
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        hasActionItems = hasActionVideo(absXml.movie.videoList);
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        hasActionItems = hasActionItems || hasActionVideo(absXml.movie.videoList);
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    if (maxPage>0 && page > maxPage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                        if(page>2)Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                    }else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if (page == 1) {
                        hasActionItems = false;
                        showEmpty();
                    } else if(page > 2){// 只有一页数据时不提示
                        Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                    }
                    gridAdapter.loadMoreEnd();
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty()) {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    public boolean shouldReloadOnSelect() {
        return !isRequesting && mGrids.empty() && (hasActionItems || !isLoad);
    }

    /**
     * 三好TV：判断是否为“配置中心”的“主页”分类。
     * 配置中心（key=Wexconfig）的“主页”分类返回的都是无关的推荐电影海报，
     * 进入时该 tab 内容需要留空；而“综合设置 / 各网盘”分类里的设置项必须正常加载。
     */
    private boolean isConfigCenterHome() {
        try {
            if (!"Wexconfig".equalsIgnoreCase(ApiConfig.get().getHomeSourceBean().getKey())) return false;
            if (sortData == null || sortData.name == null) return false;
            String n = sortData.name.trim();
            return "主页".equals(n) || "首页".equals(n);
        } catch (Throwable t) {
            return false;
        }
    }

    private void initData() {
        // 三好TV：任何正常加载（切 tab / 刷新 / 进文件夹）都先停止扫码静默轮询，
        // 避免其回包与正常回包串扰。静默重拉本身直接调 getList，不经过这里。
        stopSilentAuthReload();
        // 三好TV：只把配置中心“主页”tab 的内容留空（去掉推荐电影海报）；
        // 综合设置、各网盘分类的设置项照常加载。点“主页”tab 切回点播首页由 HomeActivity 处理。
        if (isConfigCenterHome()) {
            try {
                if (gridAdapter != null) gridAdapter.setNewData(new ArrayList<>());
                showSuccess();
            } catch (Throwable ignored) {
            }
            isRequesting = false;
            isLoad = false;
            return;
        }
        showLoading();
        isRequesting = true;
        isLoad = false;
        hasActionItems = false;
        scrollTop();
        toggleFilterColor();
        sourceViewModel.getList(sortData, page);
    }

    private boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private void toggleFilterColor() {
        if (sortData!=null && sortData.filters != null && !sortData.filters.isEmpty()) {
            int count = sortData.filterSelectCount();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_FILTER_CHANGE, count));
        }
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        if (mGridView == null) return;
        mGridView.scrollToPosition(0);
    }

    public void showFilter() {
        if (!sortData.filters.isEmpty() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
//            gridFilterDialog.setData(sortData);
//            gridFilterDialog.setOnDismiss(new GridFilterDialog.Callback() {
//                @Override
//                public void change() {
//                    page = 1;
//                    initData();
//                }
//            });
            setFilterDialogData();
        }
        if (gridFilterDialog != null)
            gridFilterDialog.show();
    }

    public void setFilterDialogData() {
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        assert context != null;
        final int defaultColor = ContextCompat.getColor(context, R.color.color_FFFFFF);
        final int selectedColor = ContextCompat.getColor(context, R.color.color_02F8E1);
        // 遍历过滤条件数据
        for (MovieSort.SortFilter filter : sortData.filters) {
            View line = inflater.inflate(R.layout.item_grid_filter, gridFilterDialog.filterRoot, false);
            TextView filterNameTv = line.findViewById(R.id.filterName);
            filterNameTv.setText(filter.name);
            TvRecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            GridFilterKVAdapter adapter = new GridFilterKVAdapter();
            gridView.setAdapter(adapter);
            final String key = filter.key;
            final ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            final ArrayList<String> keys = new ArrayList<>(filter.values.values());
            adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                // 用于记录上一次选中的 view
                View previousSelectedView = null;
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    String currentSelection = sortData.filterSelect.get(key);
                    String newSelection = keys.get(position);
                    if (currentSelection == null || !currentSelection.equals(newSelection)) {
                        // 更新选中状态
                        sortData.filterSelect.put(key, newSelection);
                        updateViewStyle(view, selectedColor, true);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = view;
                    } else {
                        // 取消选中
                        sortData.filterSelect.remove(key);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = null;
                    }
                    forceRefresh();
                }
                private void updateViewStyle(View view, int color, boolean isBold) {
                    TextView valueTv = view.findViewById(R.id.filterValue);
                    valueTv.getPaint().setFakeBoldText(isBold);
                    valueTv.setTextColor(color);
                }
            });
            adapter.setNewData(values);
            gridFilterDialog.filterRoot.addView(line);
        }
    }

    public void forceRefresh() {
        if (mGridView == null || gridAdapter == null || sourceViewModel == null) return;
        if (isRequesting) return;
        page = 1;
        initData();
    }

    // ===== 三好TV：扫码后静默重拉真实状态 =====
    // 关键事实：扫码其实已经成功 —— 退出配置中心再进入即可看到“已登录”，
    // 说明 spider 内部已保存登录态，只是当前页面没有重新请求数据。
    // 因此不再抓 Toast / Cookie / 强改文案，而是：点击“未扫码”卡片后，
    // 每隔一小段时间静默重新请求当前分类（不闪 loading、不动焦点），
    // 直接用 spider 返回的真实数据刷新卡片，直到数据中不再有“未扫码”为止。
    private final android.os.Handler authHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean silentAuthReloading = false;   // 当前回包是否为静默重拉
    private int authReloadTicks = 0;               // 已重拉次数
    private static final int AUTH_RELOAD_INTERVAL_MS = 1500; // 重拉间隔
    private static final int AUTH_RELOAD_MAX_TICKS = 40;     // 最多约 60 秒

    private final Runnable authReloadRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || getActivity() == null || sortData == null || sourceViewModel == null) return;
            authReloadTicks++;
            silentAuthReloading = true;
            // 重新请求当前分类第一页，走 spider 的真实逻辑（与“退出再进”同一路径）
            sourceViewModel.getList(sortData, 1);
        }
    };

    /** 点击“未扫码”授权卡片后调用：开始静默重拉真实状态。 */
    private void startSilentAuthReload() {
        if (sortData == null || sourceViewModel == null) return;
        authHandler.removeCallbacks(authReloadRunnable);
        authReloadTicks = 0;
        silentAuthReloading = false;
        authHandler.postDelayed(authReloadRunnable, AUTH_RELOAD_INTERVAL_MS);
    }

    /** 停止静默重拉（切 tab / 手动刷新 / 销毁时调用）。 */
    private void stopSilentAuthReload() {
        authHandler.removeCallbacks(authReloadRunnable);
        silentAuthReloading = false;
        authReloadTicks = 0;
    }

    /**
     * 处理静默重拉回包：用 spider 返回的真实数据更新“未扫码”卡片文案。
     * 若回包中已无“未扫码”（登录已生效）则停止轮询，否则继续重拉。
     */
    private void handleSilentAuthResult(AbsXml absXml) {
        boolean stillUnscanned = false;
        boolean hasData = absXml != null && absXml.movie != null
                && absXml.movie.videoList != null && !absXml.movie.videoList.isEmpty();
        List<Movie.Video> current = gridAdapter == null ? null : gridAdapter.getData();

        if (hasData) {
            List<Movie.Video> fresh = absXml.movie.videoList;
            for (Movie.Video f : fresh) {
                if (f != null && f.note != null && f.note.contains("未扫码")) {
                    stillUnscanned = true;
                    break;
                }
            }
            if (!stillUnscanned) {
                // spider 已认定登录成功：先把能按 id/name 匹配的卡片同步为真实文案，
                // 再兜底把剩余仍显示“未扫码”的卡片统一改为“已登录”。
                for (Movie.Video f : fresh) {
                    if (f == null || f.note == null || current == null) continue;
                    for (Movie.Video c : current) {
                        if (c == null) continue;
                        boolean same = (f.id != null && f.id.equals(c.id))
                                || (f.name != null && f.name.equals(c.name));
                        if (same) c.note = f.note;
                    }
                }
                if (current != null) {
                    for (Movie.Video c : current) {
                        if (c != null && c.note != null && c.note.contains("未扫码")) {
                            c.note = "已登录";
                        }
                    }
                }
                if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
            }
        }

        if ((!hasData || stillUnscanned) && authReloadTicks < AUTH_RELOAD_MAX_TICKS && isAdded()) {
            authHandler.postDelayed(authReloadRunnable, AUTH_RELOAD_INTERVAL_MS);
        } else {
            stopSilentAuthReload();
        }
    }

    /** 兼容旧调用：进入配置中心时无需再清理临时标记（保留空实现）。 */
    public static void clearLoggedInSources() {
    }

    @Override
    public void onDestroyView() {
        stopSilentAuthReload();
        super.onDestroyView();
    }
}
