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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.app.databinding.ItemTransferBinding

/**
 * Displays transfer progress, attempt count, timestamps, blob name, and
 * sanitized error information for the Transfer History screen (spec 14).
 */
class TransferHistoryAdapter :
    ListAdapter<LocalTransfer, TransferHistoryAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemTransferBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(transfer: LocalTransfer) {
            binding.textFileName.text = transfer.fileName
            binding.textDetails.text = "${transfer.direction} · ${transfer.state} · " +
                "attempt ${transfer.attemptCount} · ${transfer.bytesTransferred}/${transfer.fileSize} bytes" +
                (transfer.blobName?.let { " · $it" } ?: "")
            binding.textError.text = transfer.lastErrorMessage ?: ""
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<LocalTransfer>() {
        override fun areItemsTheSame(oldItem: LocalTransfer, newItem: LocalTransfer) =
            oldItem.transferId == newItem.transferId

        override fun areContentsTheSame(oldItem: LocalTransfer, newItem: LocalTransfer) =
            oldItem == newItem
    }
}
