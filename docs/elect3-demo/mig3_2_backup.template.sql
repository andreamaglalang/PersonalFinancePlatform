-- OWLI pre-migration-3 backup TEMPLATE. Do not run directly: Backup-OwliPreMig3.ps1 substitutes a unique path.
-- Writes ONE new backup file; no database changes. PREPARED, NOT EXECUTED.
-- Fail-closed: refuses an unsubstituted or unexpected path; NOINIT never overwrites an existing backup set;
-- afterwards the file must contain exactly one backup set (msdb history) and pass RESTORE VERIFYONLY WITH CHECKSUM.
SET NOCOUNT ON;
SET XACT_ABORT ON;
SET ANSI_NULLS ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON; SET ARITHABORT ON; SET CONCAT_NULL_YIELDS_NULL ON; SET QUOTED_IDENTIFIER ON; SET NUMERIC_ROUNDABORT OFF;
IF ISNULL(CONVERT(int, SERVERPROPERTY('IsLocalDB')), 0) <> 1 THROW 51601, 'STOP: expected LocalDB.', 1;
IF CONVERT(nvarchar(128), SERVERPROPERTY('MachineName')) COLLATE Latin1_General_100_BIN2 <> N'LAPTOP-MHRETQLO' THROW 51602, 'STOP: unexpected machine.', 1;
IF DB_NAME() COLLATE Latin1_General_100_BIN2 <> N'PersonalFinanceDb' THROW 51603, 'STOP: unexpected database.', 1;
IF NOT EXISTS (SELECT 1 FROM sys.database_recovery_status WHERE database_id = DB_ID()
               AND database_guid = CONVERT(uniqueidentifier, 'bfe2bf61-c692-4550-a5ad-30158344a45c'))
    THROW 51604, 'STOP: database GUID differs from the approved target.', 1;
IF @@TRANCOUNT <> 0 THROW 51605, 'STOP: active transaction context.', 1;
IF EXISTS (SELECT 1 FROM sys.databases WHERE database_id = DB_ID() AND (state_desc <> N'ONLINE' OR is_read_only = 1))
    THROW 51606, 'STOP: database not ONLINE/READ_WRITE.', 1;
GO
DECLARE @path nvarchar(260) = N'__OWLI_BACKUP_PATH__';
IF @path NOT LIKE N'D:\Final Project\db-backups\PersonalFinanceDb[_]pre[_]mig3[_]%.bak' ESCAPE N'|'
    THROW 51640, 'STOP: backup path not substituted or outside the approved pattern.', 1;
IF EXISTS (SELECT 1 FROM msdb.dbo.backupmediafamily WHERE physical_device_name = @path)
    THROW 51641, 'STOP: backup history already references this file name.', 1;
BACKUP DATABASE [PersonalFinanceDb] TO DISK = @path
    WITH COPY_ONLY, CHECKSUM, NOFORMAT, NOINIT, NAME = N'PersonalFinanceDb pre AddSavingsGoalContributionLedgerAndRowVersions', STATS = 25;
IF (SELECT COUNT(*) FROM msdb.dbo.backupmediafamily f JOIN msdb.dbo.backupset s ON s.media_set_id = f.media_set_id
    WHERE f.physical_device_name = @path AND s.database_name = N'PersonalFinanceDb' AND s.is_copy_only = 1 AND s.has_backup_checksums = 1) <> 1
    THROW 51642, 'STOP: backup file does not hold exactly one copy-only checksummed set.', 1;
RESTORE VERIFYONLY FROM DISK = @path WITH FILE = 1, CHECKSUM;
SELECT N'BACKUP VERIFIED' AS Result, @path AS BackupFile;
GO
