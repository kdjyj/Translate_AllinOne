package com.cedarxuesong.translate_allinone.utils.config;

import com.cedarxuesong.translate_allinone.utils.config.pojos.ChatTranslateConfig;
import com.cedarxuesong.translate_allinone.utils.config.pojos.CacheBackupConfig;
import com.cedarxuesong.translate_allinone.utils.config.pojos.DebugConfig;
import com.cedarxuesong.translate_allinone.utils.config.pojos.ItemTranslateConfig;
import com.cedarxuesong.translate_allinone.utils.config.pojos.ProviderManagerConfig;
import com.cedarxuesong.translate_allinone.utils.config.pojos.ScoreboardConfig;

public class ModConfig {
    public ChatTranslateConfig chatTranslate = new ChatTranslateConfig();

    public ItemTranslateConfig itemTranslate = new ItemTranslateConfig();

    public ScoreboardConfig scoreboardTranslate = new ScoreboardConfig();

    public CacheBackupConfig cacheBackup = new CacheBackupConfig();

    public DebugConfig debug = new DebugConfig();

    public ProviderManagerConfig providerManager = new ProviderManagerConfig();
}
