/**
 * Copyright Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.azure.storage.photosync.core

/**
 * Resolves filename conflicts in the download destination folder, per spec
 * section 7.3:
 *  - Use the original filename if it does not exist.
 *  - Skip if an identical completed file already exists.
 *  - If the name exists but content differs, create "name (1).ext" etc.
 *  - Never silently overwrite unless the user enabled overwriting.
 */
object FilenameConflictResolver {

    sealed class Resolution {
        /** No file with this name exists; safe to use [name] as-is. */
        data class UseOriginal(val name: String) : Resolution()

        /** A file with this exact name and matching content already exists. */
        object SkipIdenticalExisting : Resolution()

        /** A file with this name exists but its content differs; use [name] instead. */
        data class UseAlternateName(val name: String) : Resolution()

        /** The user has enabled overwriting; the existing file may be replaced. */
        data class Overwrite(val name: String) : Resolution()
    }

    /**
     * @param desiredName original (sanitized) filename.
     * @param existingFileMatches function returning true if a file with the
     *   given candidate name exists in the destination and its content
     *   (size/checksum) already matches the incoming content.
     * @param existingFileExists function returning true if a file with the
     *   given candidate name exists in the destination, regardless of content.
     * @param allowOverwrite whether the user has enabled overwriting existing
     *   files with different content (default: false, per spec).
     * @param maxAttempts safety bound on "(n)" suffix search.
     */
    fun resolve(
        desiredName: String,
        existingFileExists: (String) -> Boolean,
        existingFileMatches: (String) -> Boolean,
        allowOverwrite: Boolean = false,
        maxAttempts: Int = 1000
    ): Resolution {
        if (!existingFileExists(desiredName)) {
            return Resolution.UseOriginal(desiredName)
        }
        if (existingFileMatches(desiredName)) {
            return Resolution.SkipIdenticalExisting
        }
        if (allowOverwrite) {
            return Resolution.Overwrite(desiredName)
        }

        val (base, ext) = splitExtension(desiredName)
        for (attempt in 1..maxAttempts) {
            val candidate = if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext"
            if (!existingFileExists(candidate)) {
                return Resolution.UseAlternateName(candidate)
            }
            if (existingFileMatches(candidate)) {
                return Resolution.SkipIdenticalExisting
            }
        }
        error("Unable to resolve a unique filename for '$desiredName' after $maxAttempts attempts")
    }

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot in 1 until name.length - 1) {
            name.substring(0, dot) to name.substring(dot + 1)
        } else {
            name to ""
        }
    }
}
