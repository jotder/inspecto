package com.gamma.backup;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/** The Signal Types the backup module posts (SIGNAL-BOARD-1 SB-1b), fields read from {@link BackupTask}. */
public final class BackupSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:backup"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(
                new SignalTypeDecl(BackupSignals.BACKUP_COMPLETED, "Backup written", "job-type:maintenance", List.of(
                        SignalField.always("archive", Kind.STRING, "The archive written"),
                        SignalField.always("archiveSha256", Kind.STRING, "The archive's SHA-256"),
                        SignalField.always("fileCount", Kind.INTEGER, "Files archived"),
                        SignalField.always("totalBytes", Kind.INTEGER, "Bytes archived")), "4.0.0"),
                new SignalTypeDecl(BackupSignals.BACKUP_VERIFY_FAILED, "Backup verification failed", "job-type:maintenance", List.of(
                        SignalField.always("backupDir", Kind.STRING, "The backup folder checked"),
                        SignalField.always("count", Kind.INTEGER, "How many findings"),
                        SignalField.always("findings", Kind.LIST, "The findings")), "4.0.0"),
                new SignalTypeDecl(BackupSignals.RESTORE_COMPLETED, "Backup restored", "job-type:maintenance", List.of(
                        SignalField.always("archive", Kind.STRING, "The archive restored"),
                        SignalField.always("target", Kind.STRING, "Where it was restored"),
                        SignalField.always("fileCount", Kind.INTEGER, "Files restored")), "4.0.0"));
    }
}
