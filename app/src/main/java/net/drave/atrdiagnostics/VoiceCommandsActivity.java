package net.drave.atrdiagnostics;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.speech.*;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import static net.drave.atrdiagnostics.Gabi.Role.*;

/** Explicit, short local speech sessions. Leaving the screen or losing audio focus cancels the microphone. */
public final class VoiceCommandsActivity extends Activity implements RecognitionListener {
    private NavSession s;private VoiceOutput output;private SpeechRecognizer recognizer;
    private AudioManager audio;private AudioFocusRequest focus;private boolean listening,ownsFocus;
    private TextView status,listen,confirm;private EditText text;
    private long confirmUntil;private long muteUntil;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable timeout=()->{cancelListening();status.setText("Listening timed out. Tap Listen to try again.");};
    public void onCreate(Bundle state){
        super.onCreate(state);s=NavSession.get(this);Gabi.Kit k=new Gabi.Kit(this,new Gabi.Themer(Gabi.resolve(this,s.lat,s.lon,s.fixAt>0)));
        GabiPage page=new GabiPage(this,k,"Offline commands");
        page.content.addView(k.text("Repeat a turn, ask how long or how far, mute guidance, or end a ride. No account or internet is needed.",15,550,INK));
        status=k.text("Ready",15,650,INK);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);page.content.addView(status);
        listen=k.primary("Listen for a command",this::listen);page.content.addView(listen);
        page.content.addView(k.ghost("Cancel listening",this::cancelListening));
        page.content.addView(k.text("Uses Android’s current microphone and an installed on-device recognizer. Test helmet microphone and audio behavior while parked. Listening stops after one command or 10 seconds.",13,500,MUTED));
        text=new EditText(this);text.setHint("Or type a command while parked");text.setSingleLine();text.setTextSize(16);text.setMinHeight(k.dp(52));k.t.add(p->{text.setTextColor(p.ink);text.setHintTextColor(p.muted);});page.content.addView(text);
        page.content.addView(k.ghost("Run typed command",()->execute(text.getText().toString())));
        confirm=k.ghost("Confirm end ride",()->execute("confirm stop"));confirm.setVisibility(View.GONE);page.content.addView(confirm);
        LinearLayout examples=GabiPage.card(k);examples.addView(k.label("Try saying"));examples.addView(k.text("“Repeat the next instruction”\n“How long until I arrive?”\n“How far to go?”\n“Mute guidance” / “Unmute guidance”\n“End ride” · asks for confirmation",14,500,INK));page.content.addView(examples);
        output=new VoiceOutput(this,message->{});audio=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change->{if(change<0){cancelListening();status.setText("Listening stopped for another audio session");}},main).build();
        if(Build.VERSION.SDK_INT<31||!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){listen.setEnabled(false);listen.setAlpha(.45f);status.setText("No on-device speech recognizer is available. Typed commands still work offline.");}
    }
    private void listen(){
        if(listening)return;
        if(Build.VERSION.SDK_INT<31||!SpeechRecognizer.isOnDeviceRecognitionAvailable(this))return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},83);return;}
        if(audio==null||audio.getMode()==AudioManager.MODE_IN_CALL||audio.getMode()==AudioManager.MODE_IN_COMMUNICATION){status.setText("Finish the current call before listening");return;}
        output.stop();if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){status.setText("Audio is busy. Try after the current audio session.");return;}ownsFocus=true;
        try{if(recognizer==null){recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);recognizer.setRecognitionListener(this);}
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
            s.commandAudioUntil=SystemClock.elapsedRealtime()+12000;listening=true;listen.setEnabled(false);status.setText("Listening…");recognizer.startListening(intent);main.postDelayed(timeout,10000);
        }catch(RuntimeException e){cancelListening();status.setText("Local speech recognition could not start. Check Android’s installed speech model.");}
    }
    private void release(){main.removeCallbacks(timeout);listening=false;s.commandAudioUntil=0;if(listen!=null)listen.setEnabled(Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this));if(ownsFocus){ownsFocus=false;audio.abandonAudioFocusRequest(focus);}}
    private void cancelListening(){boolean was=listening;release();if(recognizer!=null&&was)recognizer.cancel();}
    private void reply(String answer){status.setText(answer);s.commandAudioUntil=SystemClock.elapsedRealtime()+4000;if(output!=null)output.speak(answer);}
    private void execute(String transcript){
        cancelListening();LocalCommands.Action action=LocalCommands.match(transcript);
        boolean route=!s.shape.isEmpty();
        switch(action){
            case REPEAT:reply(route?(s.arrived?"Destination nearby":GuidancePrompts.distance(s.distanceToTurn)+". "+s.instruction):"Start a ride to hear directions");break;
            case ETA:reply(route?"About "+Gabi.minutes(s.seconds)+" remaining. This is an estimate without live traffic.":"No route is selected");break;
            case DISTANCE:reply(route?Gabi.distance(s.remaining)+" remaining":"No route is selected");break;
            // Muting needs a second word, like ending a ride: a misheard "quiet" mustn't silence directions for later rides.
            case MUTE:muteUntil=SystemClock.elapsedRealtime()+15000;reply("Mute directions? Say confirm mute, or cancel.");break;
            case CONFIRM_MUTE:if(muteUntil>SystemClock.elapsedRealtime()){muteUntil=0;getSharedPreferences("ui",0).edit().putBoolean("voice",false).apply();if(s.active)startService(new Intent(this,NavService.class).setAction("audio_stop"));reply("Directions muted. Say unmute to turn them back on.");}else reply("Say mute first");break;
            case UNMUTE:getSharedPreferences("ui",0).edit().putBoolean("voice",true).apply();reply("Guidance on");break;
            case STOP:if(!s.active){reply("No ride is running");break;}confirmUntil=SystemClock.elapsedRealtime()+15000;confirm.setVisibility(View.VISIBLE);reply("End this ride? Say confirm stop, or cancel.");main.postDelayed(()->{if(SystemClock.elapsedRealtime()>=confirmUntil)confirm.setVisibility(View.GONE);},15000);break;
            case CONFIRM_STOP:if(s.active&&confirmUntil>SystemClock.elapsedRealtime()){confirmUntil=0;confirm.setVisibility(View.GONE);startService(new Intent(this,NavService.class).setAction("stop"));reply("Ride ended");}else reply("Say end ride first");break;
            case CANCEL:confirmUntil=0;muteUntil=0;confirm.setVisibility(View.GONE);reply("Cancelled. Your ride is unchanged.");break;
            case TRAFFIC:reply("Live traffic is unavailable. Daanvia uses offline road estimates.");break;
            default:reply("Try repeat, how long, how far, mute guidance, unmute guidance, or end ride.");
        }
        s.signal();
    }
    public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==83){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)listen();else status.setText("Microphone permission is off. Typed commands still work.");}}
    public void onReadyForSpeech(Bundle b){} public void onBeginningOfSpeech(){} public void onRmsChanged(float value){} public void onBufferReceived(byte[] b){} public void onEndOfSpeech(){if(listening)status.setText("Recognizing on this device…");}
    public void onError(int code){if(!listening)return;release();status.setText("No command recognized ("+code+"). Check the installed English speech model or use a typed command.");}
    public void onResults(Bundle b){if(!listening)return;release();ArrayList<String> words=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(words!=null&&!words.isEmpty()){text.setText(words.get(0));execute(words.get(0));}}
    public void onPartialResults(Bundle b){}public void onEvent(int type,Bundle b){}
    protected void onPause(){cancelListening();if(output!=null)output.stop();super.onPause();}
    protected void onDestroy(){main.removeCallbacksAndMessages(null);if(recognizer!=null)recognizer.destroy();if(output!=null)output.close();super.onDestroy();}
}
