package modder.hub.dexeditor.utils;

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Publishes staged DEX files with per-file atomic renames and a journal for interrupted publication.
 * Process death can expose a partial set until the next ClassTree load rolls it back from the journal.
 */
final class DexFilePublisher {
    private static final String JOURNAL_NAME = ".dex-editor-publish.properties";

    private DexFilePublisher() {}

    static synchronized void publishAtomically(List<? extends StagedFile> files) throws IOException {
        if (files.isEmpty()) return;
        List<Publication> publications = new ArrayList<>();
        File journal = new File(files.get(0).outputFile.getParentFile(), JOURNAL_NAME);
        boolean journalWritten = false;
        boolean complete = false;
        try {
            for (StagedFile file : files) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("DEX save cancelled before publish.");
                if (!file.outputFile.getParentFile().getCanonicalFile()
                        .equals(journal.getParentFile().getCanonicalFile())) {
                    throw new IOException("All DEX outputs must be in the same directory.");
                }
                File rollbackFile = null;
                boolean existed = file.outputFile.exists();
                if (existed) {
                    rollbackFile = File.createTempFile(file.fileName + ".", ".rollback",
                            file.outputFile.getParentFile());
                    copyFile(file.outputFile, rollbackFile);
                }
                Publication publication = new Publication(file, existed, rollbackFile);
                publications.add(publication);
                if (existed) publishBackup(file.outputFile,
                        new File(file.outputFile.getAbsolutePath() + ".bak"));
            }

            writeJournal(journal, publications);
            journalWritten = true;

            for (Publication publication : publications) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("DEX save cancelled during publish.");
                try {
                    Os.rename(publication.file.tempFile.getAbsolutePath(),
                            publication.file.outputFile.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("Could not publish compiled DEX file: "
                            + publication.file.outputFile, e);
                }
            }
            if (!journal.delete()) throw new IOException("Could not clear the DEX publication journal.");
            complete = true;
        } catch (IOException failure) {
            if (journalWritten) {
                try {
                    restore(publications);
                    if (!journal.delete()) throw new IOException("Could not clear the DEX publication journal after rollback.");
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        } finally {
            for (Publication publication : publications) {
                if ((complete || !journal.exists()) && publication.rollbackFile != null
                        && publication.rollbackFile.exists()) {
                    publication.rollbackFile.delete();
                }
            }
        }
    }

    static synchronized void recoverPendingPublication(List<String> sourceDexPaths) throws IOException {
        if (sourceDexPaths == null || sourceDexPaths.isEmpty()) return;
        File directory = new File(sourceDexPaths.get(0)).getCanonicalFile().getParentFile();
        File journal = new File(directory, JOURNAL_NAME);
        if (!journal.exists()) return;

        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(journal)) {
            properties.load(input);
        }
        if (!"1".equals(properties.getProperty("version"))) {
            throw new IOException("Unrecognized DEX publication journal: " + journal);
        }
        int count;
        try {
            count = Integer.parseInt(properties.getProperty("count", "-1"));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid DEX publication journal: " + journal, e);
        }
        if (count < 1 || count > 256) throw new IOException("Invalid DEX publication journal entry count.");

        List<Publication> publications = new ArrayList<>(count);
        java.util.Set<String> selectedPaths = new java.util.HashSet<>();
        for (String path : sourceDexPaths) selectedPaths.add(new File(path).getCanonicalPath());
        for (int i = 0; i < count; i++) {
            String path = properties.getProperty("output." + i);
            String existedValue = properties.getProperty("existed." + i);
            String rollbackPath = properties.getProperty("rollback." + i, "");
            if (path == null || !("true".equals(existedValue) || "false".equals(existedValue))) {
                throw new IOException("Invalid DEX publication journal entry " + i + ".");
            }
            File output = new File(path).getCanonicalFile();
            if (!selectedPaths.contains(output.getPath()) || !directory.equals(output.getParentFile())) {
                throw new IOException("DEX publication journal does not match the selected DEX set.");
            }
            File rollback = rollbackPath.isEmpty() ? null : new File(rollbackPath).getCanonicalFile();
            if ("true".equals(existedValue) && (rollback == null
                    || !directory.equals(rollback.getParentFile())
                    || !rollback.getName().startsWith(output.getName() + ".")
                    || !rollback.getName().endsWith(".rollback"))) {
                throw new IOException("Invalid rollback file in DEX publication journal.");
            }
            publications.add(new Publication(new StagedFile(output.getName(), output, null),
                    "true".equals(existedValue), rollback));
        }

        restore(publications);
        if (!journal.delete()) throw new IOException("Could not clear recovered DEX publication journal.");
        deleteRollbackFiles(publications);
    }

    private static void writeJournal(File journal, List<Publication> publications) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("count", Integer.toString(publications.size()));
        for (int i = 0; i < publications.size(); i++) {
            Publication publication = publications.get(i);
            properties.setProperty("output." + i, publication.file.outputFile.getCanonicalPath());
            properties.setProperty("existed." + i, Boolean.toString(publication.existed));
            properties.setProperty("rollback." + i, publication.rollbackFile == null
                    ? "" : publication.rollbackFile.getCanonicalPath());
        }
        File temporary = File.createTempFile(".dex-editor-publish-", ".partial", journal.getParentFile());
        boolean published = false;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                properties.store(output, "DEX publication rollback data");
                output.getFD().sync();
            }
            try {
                Os.rename(temporary.getAbsolutePath(), journal.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("Could not publish DEX transaction journal.", e);
            }
            published = true;
        } finally {
            if (!published && temporary.exists()) temporary.delete();
        }
    }

    private static void restore(List<Publication> publications) throws IOException {
        for (int i = publications.size() - 1; i >= 0; i--) {
            Publication publication = publications.get(i);
            if (publication.existed) {
                if (publication.rollbackFile == null || !publication.rollbackFile.isFile()) {
                    throw new IOException("Missing DEX rollback data for " + publication.file.outputFile);
                }
                File restored = File.createTempFile(".dex-editor-restore-", ".partial",
                        publication.file.outputFile.getParentFile());
                boolean moved = false;
                try {
                    copyFile(publication.rollbackFile, restored);
                    try {
                        Os.rename(restored.getAbsolutePath(), publication.file.outputFile.getAbsolutePath());
                    } catch (ErrnoException e) {
                        throw new IOException("Could not restore DEX file: " + publication.file.outputFile, e);
                    }
                    moved = true;
                } finally {
                    if (!moved && restored.exists()) restored.delete();
                }
            } else if (publication.file.outputFile.exists() && !publication.file.outputFile.delete()) {
                throw new IOException("Could not remove partially published DEX file: "
                        + publication.file.outputFile);
            }
        }
    }

    private static void deleteRollbackFiles(List<Publication> publications) {
        for (Publication publication : publications) {
            if (publication.rollbackFile != null && publication.rollbackFile.exists()) {
                publication.rollbackFile.delete();
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void publishBackup(File source, File backup) throws IOException {
        File temporaryBackup = File.createTempFile(backup.getName() + ".", ".partial", backup.getParentFile());
        boolean published = false;
        try {
            copyFile(source, temporaryBackup);
            try {
                Os.rename(temporaryBackup.getAbsolutePath(), backup.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("Could not publish DEX backup: " + backup, e);
            }
            published = true;
        } finally {
            if (!published && temporaryBackup.exists()) temporaryBackup.delete();
        }
    }

    static class StagedFile {
        final String fileName;
        final File outputFile;
        final File tempFile;

        StagedFile(String fileName, File outputFile, File tempFile) {
            this.fileName = fileName;
            this.outputFile = outputFile;
            this.tempFile = tempFile;
        }
    }

    private static final class Publication {
        final StagedFile file;
        final boolean existed;
        final File rollbackFile;

        Publication(StagedFile file, boolean existed, File rollbackFile) {
            this.file = file;
            this.existed = existed;
            this.rollbackFile = rollbackFile;
        }
    }
}
