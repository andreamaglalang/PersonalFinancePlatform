BEGIN TRANSACTION;
ALTER TABLE [SavingsGoals] ADD [RowVersion] rowversion NOT NULL;

ALTER TABLE [GoalContributions] ADD [RowVersion] rowversion NOT NULL;

CREATE TABLE [GoalContributionRequests] (
    [RequestId] bigint NOT NULL IDENTITY,
    [UserId] int NOT NULL,
    [ClientId] uniqueidentifier NULL,
    [GoalId] int NOT NULL,
    [ContributionId] int NOT NULL,
    [OriginalAmount] decimal(18,2) NOT NULL,
    [OriginalContributedOn] date NOT NULL,
    [OriginalNote] nvarchar(200) NULL,
    [CanonicalVersion] smallint NOT NULL,
    [RecordedAtUtc] datetime2 NOT NULL,
    [DeletedAtUtc] datetime2 NULL,
    CONSTRAINT [PK_GoalContributionRequests] PRIMARY KEY ([RequestId]),
    CONSTRAINT [CK_GoalContributionRequests_Amount] CHECK ([OriginalAmount] > 0),
    CONSTRAINT [CK_GoalContributionRequests_CanonicalVersion] CHECK ([CanonicalVersion] = 1),
    CONSTRAINT [CK_GoalContributionRequests_ClientId] CHECK ([ClientId] IS NULL OR [ClientId] <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT [CK_GoalContributionRequests_ContributionId] CHECK ([ContributionId] > 0),
    CONSTRAINT [CK_GoalContributionRequests_DeletedAtUtc] CHECK ([DeletedAtUtc] IS NULL OR [DeletedAtUtc] >= [RecordedAtUtc]),
    CONSTRAINT [FK_GoalContributionRequests_AspNetUsers_UserId] FOREIGN KEY ([UserId]) REFERENCES [AspNetUsers] ([Id]),
    CONSTRAINT [FK_GoalContributionRequests_SavingsGoals_GoalId] FOREIGN KEY ([GoalId]) REFERENCES [SavingsGoals] ([GoalId])
);

CREATE UNIQUE INDEX [IX_GoalContributionRequests_ContributionId] ON [GoalContributionRequests] ([ContributionId]);

CREATE INDEX [IX_GoalContributionRequests_GoalId] ON [GoalContributionRequests] ([GoalId]);

CREATE UNIQUE INDEX [IX_GoalContributionRequests_UserId_ClientId] ON [GoalContributionRequests] ([UserId], [ClientId]) WHERE [ClientId] IS NOT NULL;

INSERT INTO [__EFMigrationsHistory] ([MigrationId], [ProductVersion])
VALUES (N'20261103141326_AddSavingsGoalContributionLedgerAndRowVersions', N'10.0.13');

COMMIT;
GO

