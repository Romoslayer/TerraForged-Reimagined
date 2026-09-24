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

package com.terraforged.mod.mixin.common;

import com.terraforged.mod.hooks.DatapackHook;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Adds TerraForged's own {@link RepositorySource} to every {@link PackRepository}, so the built-in
 * datapack can be offered from it later.
 *
 * <p>In 1.19 this constructor also took a {@code Pack.PackConstructor}, which put the sources array
 * at local index 2; {@code PackConstructor} is gone and the constructor is now just
 * {@code PackRepository(RepositorySource...)}, leaving the array at index 1 (index 0 being
 * {@code this}).
 */
@Mixin(PackRepository.class)
public class MixinPackRepository {
    @ModifyVariable(
            at = @At("HEAD"),
            index = 1,
            target = @Desc(
                    value = "<init>",
                    args = RepositorySource[].class
            )
    )
    // Must be static: the injection point is before the super() call, where `this` does not exist yet.
    // Mixin rejects a non-static handler there outright ("@ModifyVariable handler before super()
    // invocation must be static"), which is a load-time failure, not a compile error.
    private static RepositorySource[] modifySources(RepositorySource[] sources) {
        return DatapackHook.injectRepositorySource(sources);
    }
}
