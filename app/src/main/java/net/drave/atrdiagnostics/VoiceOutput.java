package net.drave.atrdiagnostics;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Navigation speech uses installed voices and releases audio focus after every short announcement. */
final class VoiceOutput implements AutoCloseable {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final Consumer<String> status;
    private TextToSpeech tts;
    private boolean ready,closed,ownsFocus;
    private String current="";
    private long sequence;
    private final AudioAttributes attributes=new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();

    VoiceOutput(Context context,Consumer<String> status){
        this.context=context.getApplicationContext();this.status=status;audio=context.getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false)
            // A notification sound only lowers a prompt's volume for a moment; a call or another app taking over stops it.
            .setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)stop();},main).build();
        tts=new TextToSpeech(this.context,result->main.post(()->initialize(result)));
    }
    private void initialize(int result){
        if(closed)return;
        if(result!=TextToSpeech.SUCCESS){context.getSharedPreferences("ui",0).edit().putBoolean("offline_voice_ready",false).putLong("offline_voice_checked",System.currentTimeMillis()).apply();report("Voice engine unavailable. Install an offline English voice in Android's text-to-speech settings.");return;}
        tts.setAudioAttributes(attributes);
        Voice chosen=null;
        Set<Voice> voices=tts.getVoices();
        if(voices!=null)for(Voice voice:voices){
            Set<String> features=voice.getFeatures();
            if(voice.isNetworkConnectionRequired()||!"en".equals(voice.getLocale().getLanguage())
                ||features!=null&&features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))continue;
            if(chosen==null||Locale.US.equals(voice.getLocale())&&!Locale.US.equals(chosen.getLocale()))chosen=voice;
        }
        boolean offline=chosen!=null&&tts.setVoice(chosen)==TextToSpeech.SUCCESS;
        // No offline English voice installed: use the phone's default English voice, as Daanvia always did, rather than
        // riding in silence. It may need signal on some phones.
        int fallback=offline?TextToSpeech.SUCCESS:tts.setLanguage(Locale.US);
        ready=offline||fallback!=TextToSpeech.LANG_MISSING_DATA&&fallback!=TextToSpeech.LANG_NOT_SUPPORTED;
        context.getSharedPreferences("ui",0).edit().putBoolean("offline_voice_ready",offline)
            .putLong("offline_voice_checked",System.currentTimeMillis()).apply();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){}
            public void onDone(String id){main.post(()->finish(id));}
            public void onError(String id){main.post(()->{finish(id);report("Voice playback failed. Check your speech voice and audio output.");});}
            public void onStop(String id,boolean interrupted){main.post(()->finish(id));}
        });
        report(offline?"Offline English voice ready":ready?"Using the phone's default voice. For directions without signal, install an offline English voice in Android's text-to-speech settings.":"Install an English voice in Android's text-to-speech settings to hear directions.");
    }
    boolean ready(){return ready&&!closed;}
    boolean speak(String text){
        if(!ready()||text==null||text.trim().isEmpty()||audio==null)return false;
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return false;
        ownsFocus=true;current="daanvia-"+(++sequence);
        if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,current)!=TextToSpeech.SUCCESS){stop();return false;}
        return true;
    }
    private void finish(String id){if(current.equals(id)){current="";release();}}
    private void release(){if(ownsFocus&&audio!=null){ownsFocus=false;audio.abandonAudioFocusRequest(focus);}}
    void stop(){current="";if(tts!=null)tts.stop();release();}
    private void report(String message){if(status!=null&&!closed)status.accept(message);}
    public void close(){closed=true;stop();if(tts!=null){tts.shutdown();tts=null;}main.removeCallbacksAndMessages(null);}
}
