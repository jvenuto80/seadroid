package com.seafile.seadroid2.framework.datastore;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Represents a sync mapping from a Seafile repo/folder to a local Android folder.
 */
public class SyncRule {
    public String id;
    public String repoId;
    public String repoName;
    public String remotePath;  // path within repo, "/" for root
    public String localUri;    // SAF tree URI string
    public String localName;   // display name for the local folder
    public boolean enabled;

    public SyncRule() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
    }

    public SyncRule(String repoId, String repoName, String remotePath, String localUri, String localName) {
        this.id = UUID.randomUUID().toString();
        this.repoId = repoId;
        this.repoName = repoName;
        this.remotePath = remotePath;
        this.localUri = localUri;
        this.localName = localName;
        this.enabled = true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("repoId", repoId);
        obj.put("repoName", repoName);
        obj.put("remotePath", remotePath);
        obj.put("localUri", localUri);
        obj.put("localName", localName);
        obj.put("enabled", enabled);
        return obj;
    }

    public static SyncRule fromJson(JSONObject obj) throws JSONException {
        SyncRule rule = new SyncRule();
        rule.id = obj.getString("id");
        rule.repoId = obj.getString("repoId");
        rule.repoName = obj.getString("repoName");
        rule.remotePath = obj.getString("remotePath");
        rule.localUri = obj.getString("localUri");
        rule.localName = obj.optString("localName", "");
        rule.enabled = obj.optBoolean("enabled", true);
        return rule;
    }

    /**
     * Check if a given repo/path falls within this sync rule's scope.
     * A file matches if it's in the same repo and under the rule's remote path.
     */
    public boolean matches(String fileRepoId, String filePath) {
        if (!enabled || repoId == null || fileRepoId == null) {
            return false;
        }
        if (!repoId.equals(fileRepoId)) {
            return false;
        }
        if ("/".equals(remotePath)) {
            return true;
        }
        String normalizedRemote = remotePath.endsWith("/") ? remotePath : remotePath + "/";
        return filePath.startsWith(normalizedRemote) || filePath.equals(remotePath);
    }

    /**
     * Returns the relative path of the file within this sync rule's scope.
     * For example, if remotePath is "/Documents/" and filePath is "/Documents/work/report.pdf",
     * this returns "work/report.pdf".
     */
    public String getRelativePath(String filePath) {
        if ("/".equals(remotePath)) {
            return filePath.startsWith("/") ? filePath.substring(1) : filePath;
        }
        String normalizedRemote = remotePath.endsWith("/") ? remotePath : remotePath + "/";
        if (filePath.startsWith(normalizedRemote)) {
            return filePath.substring(normalizedRemote.length());
        }
        // File is at the root of the sync path
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            return filePath.substring(lastSlash + 1);
        }
        return filePath;
    }

    public String getDisplaySummary() {
        String remote = repoName;
        if (remotePath != null && !"/".equals(remotePath)) {
            remote += remotePath;
        }
        return remote + " → " + (localName != null && !localName.isEmpty() ? localName : "...");
    }
}
