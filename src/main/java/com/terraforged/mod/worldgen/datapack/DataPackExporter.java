/*
 * MIT License
 *
 * Copyright (c) 2021 TerraForged
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.terraforged.mod.worldgen.datapack;

import com.terraforged.mod.CommonAPI;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.FileUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DataPackExporter {
    public static final String PACK_NAME = TerraForged.TITLE + "-" + TerraForged.DATAPACK_VERSION;
    public static final String PACK_FILE_NAME = PACK_NAME + ".zip";

    public static final Path CONFIG_DIR = Paths.get("config", "terraforged").toAbsolutePath();
    public static final Path DEFAULT_PACK_DIR = CONFIG_DIR.resolve("pack-" + TerraForged.DATAPACK_VERSION);

    /**
     * Extracts the bundled datapack to the config directory, replacing whatever was there.
     *
     * <p>The delete is the important part. {@link FileUtil#createDirCopy} skips any file that already
     * exists, so without it an extraction done by an older build is never refreshed: its
     * {@code pack.mcmeta} keeps whatever {@code pack_format} it had, files deleted from the bundle
     * linger as orphans, and the pack quietly stays broken while looking present. That is exactly how
     * a stale {@code pack_format: 10} survived into 26.2 and showed up in-game as "Incompatible (Made
     * for an older version of Minecraft)" even though the jar shipped the corrected one.
     *
     * <p>This directory is mod-managed output, not user config — it is rewritten on every launch, so
     * do not hand-edit it and expect changes to survive. Copy it elsewhere first.
     */
    public static void extractDefaultPack() {
        try {
            TerraForged.LOG.info("Extracting default datapack to {}", DEFAULT_PACK_DIR);

            FileUtil.delete(DEFAULT_PACK_DIR);

            var root = CommonAPI.get().getContainer();
            FileUtil.createDirCopy(root, "default", DEFAULT_PACK_DIR);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Pair<Path, String> getDefaultsPath() {
        if (!Files.exists(DEFAULT_PACK_DIR)) {
            extractDefaultPack();

            if (!Files.exists(DEFAULT_PACK_DIR)) {
                TerraForged.LOG.warn("Failed to extract default datapack to {}", DEFAULT_PACK_DIR);
                return Pair.of(CommonAPI.get().getContainer(), "default");
            }
        }
        return Pair.of(DEFAULT_PACK_DIR, ".");
    }

    public static void createWorldDatapack(Path dir) {
        TerraForged.LOG.info("Copying world-instance datapack to {}", dir);

        try {
            var src = getDefaultsPath();
            var dest = dir.resolve(PACK_FILE_NAME);
            FileUtil.createZipCopy(src.getLeft(), src.getRight(), dest);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
