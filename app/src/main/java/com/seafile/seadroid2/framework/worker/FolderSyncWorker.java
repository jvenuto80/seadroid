package com.seafile.seadroid2.framework.worker;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.blankj.utilcode.util.NetworkUtils;
import com.seafile.seadroid2.SeafException;
import com.seafile.seadroid2.account.Account;
import com.seafile.seadroid2.account.SupportAccountManager;
import com.seafile.seadroid2.enums.FeatureDataSource;
import com.seafile.seadroid2.enums.SaveTo;
import com.seafile.seadroid2.enums.TransferStatus;
import com.seafile.seadroid2.framework.datastore.DataManager;
import com.seafile.seadroid2.framework.datastore.SyncRule;
import com.seafile.seadroid2.framework.datastore.SyncRuleManager;
import com.seafile.seadroid2.framework.http.HttpIO;
import com.seafile.seadroid2.framework.model.dirents.DirentRecursiveFileModel;
import com.seafile.seadroid2.framework.service.BackupThreadExecutor;
import com.seafile.seadroid2.framework.util.SafeLogs;
import com.seafile.seadroid2.framework.util.Utils;
import com.seafile.seadroid2.framework.worker.queue.TransferModel;
import com.seafile.seadroid2.ui.file.FileService;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic background worker that syncs files bidirectionally between
 * Seafile repo folders and local Android folders based on configured sync rules.
 */
public class FolderSyncWorker extends Worker {
    public static final String TAG = "FolderSyncWorker";
    public static final UUID UID = UUID.nameUUIDFromBytes(TAG.getBytes());

    public FolderSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        SafeLogs.d(TAG, "Folder sync worker started");

        Account account = SupportAccountManager.getInstance().getCurrentAccount();
        if (account == null) {
            SafeLogs.d(TAG, "No account logged in");
            return Result.success();
        }

        if (!NetworkUtils.isConnected()) {
            SafeLogs.d(TAG, "No network connection");
            return Result.success();
        }

        List<SyncRule> rules = SyncRuleManager.getAll();
        if (rules.isEmpty()) {
            SafeLogs.d(TAG, "No sync rules configured");
            return Result.success();
        }

        int downloadCount = 0;
        int uploadCount = 0;

        for (SyncRule rule : rules) {
            if (!rule.enabled) continue;

            try {
                int[] counts = processRule(account, rule);
                downloadCount += counts[0];
                uploadCount += counts[1];
            } catch (Exception e) {
                SafeLogs.e(TAG, "Error processing sync rule: " + rule.getDisplaySummary() + " - " + e.getMessage());
            }
        }

        SafeLogs.d(TAG, "Sync scan complete. Downloads queued: " + downloadCount + ", Uploads queued: " + uploadCount);

        // Kick off download and upload tasks if anything was queued
        if (downloadCount > 0) {
            BackupThreadExecutor.getInstance().runDownloadTask();
        }
        if (uploadCount > 0) {
            BackupThreadExecutor.getInstance().runFolderSyncUploadTask();
        }

        return Result.success();
    }

    /**
     * Process a single sync rule: compare remote vs local, enqueue transfers.
     * Returns int[]{downloadCount, uploadCount}.
     */
    private int[] processRule(Account account, SyncRule rule) throws IOException {
        // 1. Fetch remote file list recursively
        List<DirentRecursiveFileModel> remoteFiles = fetchRemoteFiles(rule.repoId, rule.remotePath);

        // 2. Scan local folder via SAF
        Uri treeUri = Uri.parse(rule.localUri);
        DocumentFile localRoot = DocumentFile.fromTreeUri(getApplicationContext(), treeUri);
        if (localRoot == null || !localRoot.exists()) {
            SafeLogs.d(TAG, "Local folder not accessible for rule: " + rule.getDisplaySummary());
            return new int[]{0, 0};
        }

        // Build sets of relative paths for comparison
        // Remote: relative to rule.remotePath
        Map<String, DirentRecursiveFileModel> remoteMap = new HashMap<>();
        for (DirentRecursiveFileModel rf : remoteFiles) {
            String fullPath = rf.getParent_dir() + rf.name;
            String relativePath = rule.getRelativePath(fullPath);
            remoteMap.put(relativePath, rf);
        }

        // Local: scan recursively
        Map<String, DocumentFile> localMap = new HashMap<>();
        scanLocalFiles(localRoot, "", localMap);

        // 3. Find files to download (on server but not local)
        int downloadCount = 0;
        for (Map.Entry<String, DirentRecursiveFileModel> entry : remoteMap.entrySet()) {
            String relativePath = entry.getKey();
            if (!localMap.containsKey(relativePath)) {
                enqueueDownload(account, rule, entry.getValue());
                downloadCount++;
            }
        }

        // 4. Find files to upload (local but not on server)
        int uploadCount = 0;
        for (Map.Entry<String, DocumentFile> entry : localMap.entrySet()) {
            String relativePath = entry.getKey();
            if (!remoteMap.containsKey(relativePath)) {
                DocumentFile localFile = entry.getValue();
                enqueueUpload(account, rule, relativePath, localFile);
                uploadCount++;
            }
        }

        return new int[]{downloadCount, uploadCount};
    }

    private List<DirentRecursiveFileModel> fetchRemoteFiles(String repoId, String path) throws IOException {
        retrofit2.Response<List<DirentRecursiveFileModel>> response =
                HttpIO.getCurrentInstance()
                        .execute(FileService.class)
                        .getDirRecursiveFileCall(repoId, path)
                        .execute();
        if (!response.isSuccessful() || response.body() == null) {
            return java.util.Collections.emptyList();
        }
        return response.body();
    }

    /**
     * Recursively scan a SAF DocumentFile tree, collecting files with their relative paths.
     */
    private void scanLocalFiles(DocumentFile dir, String prefix, Map<String, DocumentFile> result) {
        if (dir == null || !dir.exists()) return;
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;

        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null) continue;

            String relativePath = prefix.isEmpty() ? name : prefix + "/" + name;
            if (child.isDirectory()) {
                scanLocalFiles(child, relativePath, result);
            } else {
                result.put(relativePath, child);
            }
        }
    }

    private void enqueueDownload(Account account, SyncRule rule, DirentRecursiveFileModel remoteFile) {
        String fullPath = remoteFile.getParent_dir() + remoteFile.name;

        TransferModel tm = new TransferModel();
        tm.save_to = SaveTo.DB;
        tm.repo_id = rule.repoId;
        tm.repo_name = rule.repoName;
        tm.related_account = account.getSignature();
        tm.file_name = remoteFile.name;
        tm.file_size = remoteFile.size;
        tm.full_path = fullPath;
        tm.setParentPath(Utils.getParentPath(fullPath));
        tm.target_path = DataManager.getLocalFileCachePath(account, rule.repoId, rule.repoName, fullPath).getAbsolutePath();
        tm.transfer_status = TransferStatus.WAITING;
        tm.data_source = FeatureDataSource.DOWNLOAD;
        tm.created_at = System.nanoTime();
        tm.transfer_strategy = ExistingFileStrategy.REPLACE;
        tm.setId(tm.genStableId());

        GlobalTransferCacheList.DOWNLOAD_QUEUE.put(tm);
    }

    private void enqueueUpload(Account account, SyncRule rule, String relativePath, DocumentFile localFile) {
        // Compute remote target path
        String remotePath = rule.remotePath;
        if (!remotePath.endsWith("/")) remotePath += "/";
        String targetPath = remotePath + relativePath;

        TransferModel tm = new TransferModel();
        tm.save_to = SaveTo.DB;
        tm.repo_id = rule.repoId;
        tm.repo_name = rule.repoName;
        tm.related_account = account.getSignature();
        tm.file_name = localFile.getName();
        tm.file_size = localFile.length();
        tm.full_path = localFile.getUri().toString(); // content:// URI — handled by ProgressUriRequestBody
        tm.target_path = targetPath;
        tm.setParentPath(Utils.getParentPath(targetPath));
        tm.transfer_status = TransferStatus.WAITING;
        tm.data_source = FeatureDataSource.FOLDER_SYNC;
        tm.created_at = System.nanoTime();
        tm.transfer_strategy = ExistingFileStrategy.APPEND;
        tm.setId(tm.genStableId());

        GlobalTransferCacheList.FOLDER_SYNC_QUEUE.put(tm);
    }
}
