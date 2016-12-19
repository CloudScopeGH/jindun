package com.cloudspace.jindun.activity;

import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;

import com.android.volley.error.VolleyError;
import com.cloudspace.jindun.R;
import com.cloudspace.jindun.fragment.SectionsPagerAdapter;
import com.cloudspace.jindun.log.APPLog;
import com.cloudspace.jindun.model.Student;
import com.cloudspace.jindun.network.api.API;
import com.cloudspace.jindun.network.base.ApiCallback;
import com.cloudspace.jindun.rongyun.RongIMUtil;
import com.jeremyfeinstein.slidingmenu.lib.SlidingMenu;

public class MainActivity extends CustomTitleBaseActivity implements OnClickListener {
	public static final String TAG = MainActivity.class.getSimpleName();

	private SlidingMenu slidingMenu;

	private SectionsPagerAdapter mSectionsPagerAdapter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.jd_main_activity, true);
        getTitleBar().setSimpleMode("Main");


		API.getInstance().getHomeApi().getUser(TAG, 1, new ApiCallback<Student>() {
			@Override
			public void handleResult(Student result, VolleyError error) {
				if (result != null){
					APPLog.d("name:" + result.name);
				}
			}
		});

		RongIMUtil.connect();

		slidingMenu = new SlidingMenu(this);
		slidingMenu.setMode(SlidingMenu.LEFT);
		slidingMenu.setTouchModeAbove(SlidingMenu.TOUCHMODE_FULLSCREEN);
		slidingMenu.setBehindWidth(getResources().getDimensionPixelSize(R.dimen.sliding_menu_offset));
		slidingMenu.attachToActivity(this,SlidingMenu.SLIDING_CONTENT);
		slidingMenu.setMenu(R.layout.slidingmenu);

		initView();
	}

	private ViewPager mViewPager;
	private View mLy1;
	private View mLy2;
	private View mLy3;
	private View mLy4;
	private View mLy5;

	private void initView() {
		mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
		mViewPager = (ViewPager) findViewById(R.id.tabpager);
		mViewPager.setAdapter(mSectionsPagerAdapter);

		mLy1 = findViewById(R.id.ly1);
		mLy2 = findViewById(R.id.ly2);
		mLy3 = findViewById(R.id.ly3);
		mLy4 = findViewById(R.id.ly4);
		mLy5 = findViewById(R.id.ly5);

		mLy1.setOnClickListener(this);
		mLy2.setOnClickListener(this);
		mLy3.setOnClickListener(this);
		mLy4.setOnClickListener(this);
		mLy5.setOnClickListener(this);
	}


	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		//重写KeyDown事件，菜单键按下也能拉出slidingmenu
		switch(keyCode){
			case KeyEvent.KEYCODE_MENU:
				slidingMenu.toggle(true);
				break;

			default:
				break;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public void onClick(View v) {
		switch (v.getId()){
			case R.id.ly1:
				mViewPager.setCurrentItem(0);
				break;

			case R.id.ly2:
				mViewPager.setCurrentItem(1);
				break;

			case R.id.ly3:
				mViewPager.setCurrentItem(2);
				break;

			case R.id.ly4:
				mViewPager.setCurrentItem(3);
				break;

			case R.id.ly5:
				mViewPager.setCurrentItem(4);
				break;

			default:
				break;
		}
	}
}
