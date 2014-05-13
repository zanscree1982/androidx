/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.support.v17.leanback.app;

import android.graphics.Color;
import android.os.Bundle;
import android.support.v17.leanback.R;
import android.support.v17.leanback.widget.FocusHighlightHelper;
import android.support.v17.leanback.widget.ItemBridgeAdapter;
import android.support.v17.leanback.widget.Presenter;
import android.support.v17.leanback.widget.PresenterSelector;
import android.support.v17.leanback.widget.OnItemSelectedListener;
import android.support.v17.leanback.widget.Row;
import android.support.v17.leanback.widget.RowHeaderPresenter;
import android.support.v17.leanback.widget.SinglePresenterSelector;
import android.support.v17.leanback.widget.VerticalGridView;
import android.support.v7.widget.RecyclerView;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnLayoutChangeListener;

import java.util.ArrayList;
import java.util.List;

/**
 * An internal fragment containing a list of row headers.
 */
public class HeadersFragment extends BaseRowFragment {

    interface OnHeaderClickedListener {
        void onHeaderClicked();
    }

    private OnItemSelectedListener mOnItemSelectedListener;
    private OnHeaderClickedListener mOnHeaderClickedListener;
    private boolean mShow = true;
    private int mBackgroundColor;
    private boolean mBackgroundColorSet;

    private static final PresenterSelector sHeaderPresenter = new SinglePresenterSelector(
            new RowHeaderPresenter());

    public HeadersFragment() {
        setPresenterSelector(sHeaderPresenter);
    }

    public void setOnHeaderClickedListener(OnHeaderClickedListener listener) {
        mOnHeaderClickedListener = listener;
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        mOnItemSelectedListener = listener;
    }

    @Override
    protected void onRowSelected(ViewGroup parent, View view, int position, long id) {
        if (mOnItemSelectedListener != null) {
            if (position >= 0) {
                Row row = (Row) getAdapter().get(position);
                mOnItemSelectedListener.onItemSelected(null, row);
            }
        }
    }

    private final ItemBridgeAdapter.AdapterListener mAdapterListener =
            new ItemBridgeAdapter.AdapterListener() {
        @Override
        public void onCreate(ItemBridgeAdapter.ViewHolder viewHolder) {
            View headerView = viewHolder.getViewHolder().view;
            headerView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mOnHeaderClickedListener != null) {
                        mOnHeaderClickedListener.onHeaderClicked();
                    }
                }
            });
            headerView.setFocusable(true);
            headerView.setFocusableInTouchMode(true);
            headerView.addOnLayoutChangeListener(sLayoutChangeListener);
        }

        @Override
        public void onAttachedToWindow(ItemBridgeAdapter.ViewHolder viewHolder) {
            View headerView = viewHolder.getViewHolder().view;
            if (mInTransition) {
                updateHeaderViewForTransition(headerView, viewHolder.getPosition());
            }
        }
    };

    /**
     * Preparing headerView for transition.
     */
    private void updateHeaderViewForTransition(View headerView, Integer position) {
        final VerticalGridView listView = getVerticalGridView();
        if (position == listView.getSelectedPosition()) {
            headerView.setId(mReparentHeaderId);
        } else {
            headerView.setId(View.NO_ID);
        }
        headerView.setTag(R.id.lb_header_transition_position, position);
    }

    /**
     * Preparing all headerViews for transition.
     */
    private void updateHeaderViewsForTransition() {
        final VerticalGridView listView = getVerticalGridView();
        if (listView == null) {
            return;
        }
        final int count = listView.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = listView.getChildAt(i);
            updateHeaderViewForTransition(child,
                    listView.getChildViewHolder(child).getPosition());
        }
    }

    @Override
    void onTransitionStart() {
        super.onTransitionStart();
        updateHeaderViewsForTransition();
    }

    private static OnLayoutChangeListener sLayoutChangeListener = new OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
            v.setPivotX(0);
            v.setPivotY(v.getMeasuredHeight() / 2);
        }
    };

    @Override
    protected int getLayoutResourceId() {
        return R.layout.lb_headers_fragment;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final VerticalGridView listView = getVerticalGridView();
        if (listView == null) {
            return;
        }
        if (getBridgeAdapter() != null) {
            FocusHighlightHelper.setupHeaderItemFocusHighlight(listView);
        }
        listView.setBackgroundColor(getBackgroundColor());
    }

    void setHeadersVisiblity(boolean show) {
        mShow = show;
        final VerticalGridView listView = getVerticalGridView();
        if (listView != null) {
            listView.setLayoutEnabled(show);
        }
    }

    @Override
    protected void updateAdapter() {
        super.updateAdapter();
        ItemBridgeAdapter adapter = getBridgeAdapter();
        if (adapter != null) {
            adapter.setAdapterListener(mAdapterListener);
        }
        if (adapter != null && getVerticalGridView() != null) {
            FocusHighlightHelper.setupHeaderItemFocusHighlight(getVerticalGridView());
        }
    }

    void setBackgroundColor(int color) {
        mBackgroundColor = color;
        mBackgroundColorSet = true;

        if (getVerticalGridView() != null) {
            getVerticalGridView().setBackgroundColor(mBackgroundColor);
        }
    }

    int getBackgroundColor() {
        if (getActivity() == null) {
            throw new IllegalStateException("Activity must be attached");
        }

        if (mBackgroundColorSet) {
            return mBackgroundColor;
        }

        TypedValue outValue = new TypedValue();
        getActivity().getTheme().resolveAttribute(android.R.attr.colorBackground, outValue, true);
        return getResources().getColor(outValue.resourceId);
    }
}
