package shit.zen.modules.impl.misc;

import shit.zen.gui.MusicPlayerScreen;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.misc.music.AudioPlayer;
import shit.zen.settings.impl.NumberSetting;

import static shit.zen.ClientBase.mc;

public class MusicPlayer extends Module {
    public static final AudioPlayer AUDIO_PLAYER = new AudioPlayer();

    private boolean internalVolumeChange = false;

    private final NumberSetting volume = new NumberSetting("Volume", 80, 0, 100, 1) {
        @Override
        public void onChanged(Number oldValue, Number newValue) {
            if (internalVolumeChange) return;
            float vol = newValue.intValue() / 100f;
            System.out.println("[MusicPlayer] Setting changed: " + oldValue + " -> " + newValue + " (vol=" + vol + ")");
            AUDIO_PLAYER.setVolume(vol);
        }
    };

    public MusicPlayer() {
        super("MusicPlayer", Category.MISC);
    }

    @Override
    protected void onEnable() {
        try {
            internalVolumeChange = true;
            volume.setValue((int) (AUDIO_PLAYER.getVolume() * 100));
            internalVolumeChange = false;
            mc.setScreen(new MusicPlayerScreen());
        } catch (Exception e) {
            logger.error("Error opening MusicPlayer", e);
        } finally {
            this.setEnabled(false);
        }
    }

    public void setVolumeSetting(float vol) {
        internalVolumeChange = true;
        volume.setValue((int) (vol * 100));
        internalVolumeChange = false;
    }
}
