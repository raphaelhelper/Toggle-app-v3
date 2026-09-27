package com.example.wordpopuptest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Build test OK!");
        tv.setTextSize(24);
        tv.setPadding(40, 200, 40, 40);
        setContentView(tv);
    }
}
