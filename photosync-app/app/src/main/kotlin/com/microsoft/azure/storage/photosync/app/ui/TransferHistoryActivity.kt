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

package com.microsoft.azure.storage.photosync.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.databinding.ActivityTransferHistoryBinding
import kotlinx.coroutines.launch

/**
 * Transfer History screen (spec 14): filterable by direction/state/date/
 * filename. Filtering UI is intentionally minimal for this phase; the
 * underlying DAO query (`observeFiltered`) already supports it.
 */
class TransferHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityTransferHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = TransferHistoryAdapter()
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter

        val transferDao = AppContainer.getInstance(this).database.localTransferDao()
        lifecycleScope.launch {
            transferDao.observeAll().collect { adapter.submitList(it) }
        }
    }
}
