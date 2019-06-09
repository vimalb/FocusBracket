package com.obsidium.focusbracket;

import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import com.github.ma1co.pmcademo.app.BaseActivity;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarProperties;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class FocusActivity extends BaseActivity implements SurfaceHolder.Callback, CameraEx.ShutterListener
{
    private static final int COUNTDOWN_TICKS = 3;
    private static final int COUNTDOWN_DELAY_MS = 250;

    private class ExposureRequest {
        public int targetFocus;
        public int targetExposure;
        public ExposureRequest(int targetFocus, int targetExposure) {
            this.targetFocus = targetFocus;
            this.targetExposure = targetExposure;
        }
    }

    private SurfaceHolder       m_surfaceHolder;
    private CameraEx            m_camera;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int                 m_pictureReviewTime;

    private Handler             m_handler = new Handler();

    private TextView            m_tvMsg;
    private TextView            m_tvStatus;

    private ShootSettings       m_shootSettings = new ShootSettings();

    enum State { error, config, shoot }
    private State               m_state = State.config;

    enum SelectedControl { Shoot, AddFocusPoint, RemoveFocusPoint, SetExposureBracket }
    private SelectedControl     m_selectedControl = SelectedControl.Shoot;


    private int                 m_curFocus;
    private int                 m_focusBeforeDrive;

    private LinkedList<ExposureRequest> m_focusQueue;
    private boolean             m_waitingForFocus;

    private int                 m_countdown;
    private final Runnable      m_countDownRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            if (--m_countdown > 0)
            {
                m_tvMsg.setText(String.format("Starting in %d...", m_countdown));
                m_handler.postDelayed(this, COUNTDOWN_DELAY_MS);
            }
            else
            {
                m_tvMsg.setVisibility(View.GONE);
                startShooting();
            }
        }
    };

    private final Runnable      m_checkFocusRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            if (m_waitingForFocus && m_focusBeforeDrive == m_curFocus)
                focus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_focus);

        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof CustomExceptionHandler))
            Thread.setDefaultUncaughtExceptionHandler(new CustomExceptionHandler());

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        m_surfaceHolder = surfaceView.getHolder();
        m_surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        m_tvMsg = (TextView)findViewById(R.id.tvMsg);
        m_tvStatus = (TextView)findViewById(R.id.tvStatus);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        m_camera = CameraEx.open(0, null);
        m_surfaceHolder.addCallback(this);
        m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
        m_camera.setAutoPictureReviewControl(m_autoReviewControl);

        m_camera.setShutterListener(this);

        m_camera.setFocusDriveListener(new CameraEx.FocusDriveListener()
        {
            @Override
            public void onChanged(CameraEx.FocusPosition focusPosition, CameraEx cameraEx)
            {
                Logger.info("FocusDriveListener: currentPosition " + focusPosition.currentPosition);
                m_handler.removeCallbacks(m_checkFocusRunnable);
                m_curFocus = focusPosition.currentPosition;
                if (m_waitingForFocus)
                {
                    ExposureRequest currentExposureRequest = m_focusQueue.getFirst();
                    if (m_curFocus == currentExposureRequest.targetFocus)
                    {
                        // Focused, take picture
                        Logger.info("Taking picture (FocusDriveListener)");
                        takePicture();
                    }
                    else
                        focus();
                }
                updateDisplay();
            }
        });

        setDefaults();
        m_shootSettings = SettingSaver.load();
        setState(State.config);
    }

    // CameraEx.ShutterListener
    @Override
    public void onShutter(int i, CameraEx cameraEx)
    {
        // i: 0 = success, 1 = canceled, 2 = error
        Logger.info("onShutter (i " + i + ")");
        m_camera.cancelTakePicture();
        if (i == 0)
        {
            m_focusQueue.removeFirst();
            if (m_focusQueue.isEmpty())
            {
                setState(State.shoot);
            }
            else
            {
                // Move to next focus position
                startFocusing();
            }
        }
        updateDisplay();
    }

    private void setExposureCompensation(int exposureCompensationSteps) {
        final Camera.Parameters params = m_camera.createEmptyParameters();
        params.setExposureCompensation(exposureCompensationSteps);
        m_camera.getNormalCamera().setParameters(params);
    }

    private void setFocusMode(String value) {
        final Camera.Parameters params = m_camera.createEmptyParameters();
        params.setFocusMode(value);
        m_camera.getNormalCamera().setParameters(params);

    }

    private void takePicture()
    {
        ExposureRequest currentExposureRequest = m_focusQueue.getFirst();
        setExposureCompensation(currentExposureRequest.targetExposure);

        m_waitingForFocus = false;
        m_camera.burstableTakePicture();
        updateDisplay();
    }

    private void focus()
    {
        final ExposureRequest nextExposureRequest = m_focusQueue.getFirst();
        final int nextFocus = nextExposureRequest.targetFocus;
        m_focusBeforeDrive = m_curFocus;
        if (m_curFocus == nextFocus)
        {
            Logger.info("Taking picture (focus)");
            takePicture();
        }
        else
        {
            final int absDiff = Math.abs(m_curFocus - nextFocus);
            final int speed;
            if (absDiff > 4) {
                speed = 7;
            } else {
                speed = 4;
            }
            Logger.info("Starting focus drive (speed " + speed + ")");
            m_camera.startOneShotFocusDrive(m_curFocus < nextFocus ? CameraEx.FOCUS_DRIVE_DIRECTION_FAR : CameraEx.FOCUS_DRIVE_DIRECTION_NEAR, speed);
            // startOneShotFocusDrive won't always trigger our FocusDriveListener
            m_handler.postDelayed(m_checkFocusRunnable, 50);
        }
        updateDisplay();
    }

    private void startFocusing()
    {
        m_waitingForFocus = true;
        focus();
        updateDisplay();
    }

    private void startShooting()
    {
        m_tvMsg.setVisibility(View.GONE);
        startFocusing();
    }

    private void initFocusQueue()
    {
        m_focusQueue = new LinkedList<ExposureRequest>();
        for(int focusPoint : m_shootSettings.focusPoints) {
            m_focusQueue.addFirst(new ExposureRequest(focusPoint, 0));
            if(m_shootSettings.exposureBracket > 0) {
                m_focusQueue.addFirst(new ExposureRequest(focusPoint, m_shootSettings.exposureBracket));
                m_focusQueue.addFirst(new ExposureRequest(focusPoint, -1*m_shootSettings.exposureBracket));
            }
        }
    }

    private void updateDisplay()
    {
        if(m_state == State.config) {
            StringBuilder focusPointSummary = new StringBuilder();
            focusPointSummary.append("Focus points:");
            for(Integer focusPoint: m_shootSettings.focusPoints) {
                focusPointSummary.append(" ");
                focusPointSummary.append(focusPoint);
            }

            double exposureComp = new Integer(m_shootSettings.exposureBracket).doubleValue() / 3.0;
            String exposureBracketSummary = String.format("Exposure bracket steps: %.1f EV",exposureComp);

            m_tvStatus.setVisibility(View.VISIBLE);
            m_tvMsg.setVisibility(View.VISIBLE);
            if(m_selectedControl == SelectedControl.SetExposureBracket) {
                m_tvStatus.setText("Scroll wheel to "+m_selectedControl.name());
                m_tvMsg.setText(exposureBracketSummary);
            } else if(m_selectedControl == SelectedControl.AddFocusPoint || m_selectedControl == SelectedControl.RemoveFocusPoint) {
                m_tvStatus.setText("Press control button to "+m_selectedControl.name());
                m_tvMsg.setText(focusPointSummary.toString()+"\n\n"+"Current point: "+m_curFocus);
            } else if(m_selectedControl == SelectedControl.Shoot) {
                m_tvStatus.setText("Press control button to "+m_selectedControl.name());
                m_tvMsg.setText(focusPointSummary.toString()+"\n\n"+"Exposure bracket steps: "+m_shootSettings.exposureBracket);
            }

        } else if(m_state == State.shoot) {
            int shotsLeft = m_shootSettings.focusPoints.size();
            if(m_focusQueue != null) {
                shotsLeft = m_focusQueue.size();
            }

            m_tvStatus.setVisibility(View.VISIBLE);
            m_tvStatus.setText("Focus: "+m_curFocus+" Shots Remaining: "+shotsLeft);

            m_tvMsg.setVisibility(View.GONE);

        }
    }


    /*
        Sets camera default parameters (manual focus)
     */
    private void setDefaults()
    {
        setFocusMode(CameraEx.ParametersModifier.FOCUS_MODE_MANUAL);

        /*
            modifier.isFocusDriveSupported() returns false on ILCE-5100, focus drive is working anyway...
            This is how SmartRemote checks for it:
         */
        final String platformVersion = ScalarProperties.getString(ScalarProperties.PROP_VERSION_PLATFORM);
        if (platformVersion != null)
        {
            final String[] split = platformVersion.split("\\Q.\\E");
            if (split.length >= 2)
            {
                if (Integer.parseInt(split[1]) < 3)
                {
                    m_state = State.error;
                    m_tvMsg.setVisibility(View.VISIBLE);
                    m_tvMsg.setText("ERROR: Focus drive not supported");
                }
            }
        }

        // Disable picture review
        m_pictureReviewTime = m_autoReviewControl.getPictureReviewTime();
        m_autoReviewControl.setPictureReviewTime(0);
    }

    private void abortShooting()
    {
        m_waitingForFocus = false;
        m_handler.removeCallbacks(m_checkFocusRunnable);
        m_handler.removeCallbacks(m_countDownRunnable);
        m_focusQueue = null;
    }

    private void setState(State state)
    {
        m_state = state;
        if(m_state == State.config) {
        } else if(m_state == State.shoot) {
            SettingSaver.save(m_shootSettings);
            initFocusQueue();
            m_countdown = COUNTDOWN_TICKS;
            m_handler.postDelayed(m_countDownRunnable, COUNTDOWN_DELAY_MS);
        }
        updateDisplay();
    }

    @Override
    protected boolean onUpperDialChanged(int value)
    {
        if(m_state == State.config) {
            if(m_selectedControl == SelectedControl.SetExposureBracket) {
                if(value < 0) {
                    m_shootSettings.exposureBracket = Math.max(m_shootSettings.exposureBracket - 1, 0);
                } else {
                    m_shootSettings.exposureBracket = Math.min(9, m_shootSettings.exposureBracket + 1);
                }
            }
        }
        updateDisplay();
        return true;
    }

    @Override
    protected boolean onUpKeyDown()
    {
        List<SelectedControl> allSelectedControls = Arrays.asList(SelectedControl.values());
        int selectedIndex = allSelectedControls.indexOf(m_selectedControl);
        if(selectedIndex == 0) {
            m_selectedControl = allSelectedControls.get(allSelectedControls.size() - 1);
        } else {
            m_selectedControl = allSelectedControls.get(selectedIndex - 1);
        }
        updateDisplay();
        return true;
    }

    @Override
    protected boolean onDownKeyDown()
    {
        List<SelectedControl> allSelectedControls = Arrays.asList(SelectedControl.values());
        int selectedIndex = allSelectedControls.indexOf(m_selectedControl);
        if(selectedIndex == (allSelectedControls.size() - 1)) {
            m_selectedControl = allSelectedControls.get(0);
        } else {
            m_selectedControl = allSelectedControls.get(selectedIndex + 1);
        }
        updateDisplay();
        return true;
    }


    @Override
    protected boolean onEnterKeyUp() {
        return true;
    }



    @Override
    protected boolean onEnterKeyDown()
    {
        if(m_state == State.config) {
            if(m_selectedControl == SelectedControl.AddFocusPoint) {
                m_shootSettings.focusPoints.add(m_curFocus);
            } else if(m_selectedControl == SelectedControl.RemoveFocusPoint && m_shootSettings.focusPoints.size() > 0) {
                m_shootSettings.focusPoints.remove(m_shootSettings.focusPoints.size() - 1);
            } else if(m_selectedControl == SelectedControl.Shoot) {
                setState(State.shoot);
            }
        } else if(m_state == State.shoot) {
            abortShooting();
            setState(State.config);
        }
        updateDisplay();
        return true;
    }

    @Override
    protected boolean onMenuKeyUp()
    {
        onBackPressed();
        return true;
    }

    @Override
    protected void onPause()
    {
        super.onPause();

        abortShooting();
        setFocusMode(CameraEx.ParametersModifier.AUTO_FOCUS_MODE_AF_S);
        setExposureCompensation(0);

        m_surfaceHolder.removeCallback(this);
        m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime);
        m_camera.setAutoPictureReviewControl(null);
        m_autoReviewControl = null;
        m_camera.getNormalCamera().stopPreview();
        m_camera.release();
        m_camera = null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
        try
        {
            Camera cam = m_camera.getNormalCamera();
            cam.setPreviewDisplay(holder);
            cam.startPreview();
        }
        catch (IOException e)
        {
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {}

    @Override
    protected void setColorDepth(boolean highQuality)
    {
        super.setColorDepth(false);
    }
}
