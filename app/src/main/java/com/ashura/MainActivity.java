package com.ashura;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private Button btnToggle;
    private TextView tvStatus;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);

        btnToggle.setOnClickListener(v -> {
            if (isRunning) {
                stopService(new Intent(this, AshuraService.class));
                isRunning = false;
                btnToggle.setText("INICIAR ASHURA");
                tvStatus.setText("Detenido");
            } else {
                checkPermissionsAndStart();
            }
        });

        // Check if already running
        if (AshuraService.isRunning) {
            isRunning = true;
            btnToggle.setText("DETENER ASHURA");
            tvStatus.setText("Escuchando...");
        }
    }

    private void checkPermissionsAndStart() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            permissions = new String[]{
                Manifest.permission.RECORD_AUDIO
            };
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            startAshura();
        } else {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                startAshura();
            } else {
                Toast.makeText(this, "Permisos necesarios para funcionar", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startAshura() {
        Intent intent = new Intent(this, AshuraService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        isRunning = true;
        btnToggle.setText("DETENER ASHURA");
        tvStatus.setText("Escuchando...");
    }
}
