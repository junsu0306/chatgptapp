package com.smartglassesmanager.androidsmartphone.ui;

import android.app.Activity;

import androidx.appcompat.widget.Toolbar;

import com.smartglassesmanager.androidsmartphone.MainActivity;
import com.smartglassesmanager.androidsmartphone.R;

public class UiUtils {

    //set app bar title
    public static void setupTitle(Activity activity, String title) {
        Toolbar toolbar = (Toolbar) activity.findViewById(R.id.main_toolbar);
        ((MainActivity) activity).setSupportActionBar(toolbar);
        ((MainActivity) activity).getSupportActionBar().setTitle(title);
    }
}
