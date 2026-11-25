namespace PersonalFinance.BillPaymentSqlPreflight;

internal sealed class PreflightOrchestrator(
    IWindowsIdentityObserver windows, ILocalDbObserver localDb,
    IFilesystemObserver filesystem, IReadOnlyMetadataProbe metadata, TimeProvider clock)
{
    internal async Task<PreflightResult> RunAsync(PreflightRequest request, CancellationToken cancellationToken)
    {
        try
        {
            PreflightPolicy.Validate(request, clock.GetUtcNow());
            cancellationToken.ThrowIfCancellationRequested();
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            try
            {
                var result = await ObserveAsync(request.Scope, deadline.Token)
                    .WaitAsync(TimeSpan.FromMilliseconds(request.Scope.TimeoutMilliseconds), cancellationToken);
                cancellationToken.ThrowIfCancellationRequested();
                PreflightPolicy.Validate(request, clock.GetUtcNow());
                return result;
            }
            catch (TimeoutException)
            {
                deadline.Cancel();
                return Failed(PreflightFailure.TimedOut);
            }
            catch (OperationCanceledException)
            {
                deadline.Cancel();
                return Failed(PreflightFailure.Cancelled);
            }
        }
        catch (PreflightRejection ex) { return Failed(ex.Failure); }
        catch (OperationCanceledException) { return Failed(PreflightFailure.Cancelled); }
        catch (TimeoutException) { return Failed(PreflightFailure.TimedOut); }
        catch (Exception) { return Failed(PreflightFailure.ObservationFailed); }
    }

    private async Task<PreflightResult> ObserveAsync(ApprovedPreflightScope scope, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        var identity = await windows.ObserveAsync(ct);
        ct.ThrowIfCancellationRequested();
        PreflightPolicy.Need(identity is not null && !identity.Impersonated
            && identity.Computer == scope.WindowsOwner.Computer
            && identity.Account == scope.WindowsOwner.Account && identity.Sid == scope.WindowsOwner.Sid,
            PreflightFailure.WindowsMismatch);
        var first = await localDb.ObserveAsync(ct);
        ct.ThrowIfCancellationRequested();
        ValidateInstance(first, scope);
        var files = await filesystem.ObserveAsync(scope.Target, ct);
        ct.ThrowIfCancellationRequested();
        PreflightPolicy.Need(files is not null && files.Complete && files.ExistingAncestorsChecked
            && files.FixedLocalVolume && !files.ReparseFound && !files.AliasFound
            && !files.DataFileExists && !files.LogFileExists && files.AncestorPhysicalIdentityEstablished
            && files.DataPath == scope.Target.DataPath && files.LogPath == scope.Target.LogPath,
            PreflightFailure.FilesystemMismatch);
        var sql = await metadata.ObserveAsync(scope.Endpoint, scope.Target, ct);
        ct.ThrowIfCancellationRequested();
        PreflightPolicy.Need(sql is not null && sql.InternalServer == scope.Endpoint.InternalServer
            && sql.Version == scope.Version && sql.IsLocalDb && sql.DatabaseContext == "master"
            && sql.Login == scope.WindowsOwner.Account && sql.OriginalLogin == scope.WindowsOwner.Account
            && sql.LoginSid == scope.WindowsOwner.Sid && sql.WindowsAuthentication
            && sql.NetworkTransport == "Named pipe" && sql.TransactionCount == 0 && sql.TransactionState == 0,
            PreflightFailure.SqlIdentityMismatch);
        PreflightPolicy.Need(sql.Target is not null && sql.Target.DatabaseName == scope.Target.Name,
            PreflightFailure.ContradictoryEvidence);
        PreflightPolicy.Need(sql.CanInspectTargetMetadata && sql.Target.CompleteVisibility,
            PreflightFailure.VisibilityInsufficient);
        PreflightPolicy.Need(!sql.Target.Exists, PreflightFailure.TargetExists);
        PreflightPolicy.Need(!sql.TargetMigrationIds.IsDefault && sql.TargetMigrationIds.IsEmpty,
            PreflightFailure.ContradictoryEvidence);
        var last = await localDb.ObserveAsync(ct);
        ct.ThrowIfCancellationRequested();
        ValidateInstance(last, scope);
        PreflightPolicy.Need(first == last, PreflightFailure.ContradictoryEvidence);
        return new PreflightResult(true, PreflightFailure.None, "Offline simulated absent-target evidence matched.", true);
    }

    private static void ValidateInstance(LocalDbObservation instance, ApprovedPreflightScope scope)
    {
        PreflightPolicy.Need(instance is not null && instance.Instance == scope.Instance
            && instance.OwnerAccount == scope.InstanceOwner.Account && instance.OwnerSid == scope.InstanceOwner.Sid
            && instance.Version == scope.Version && instance.Running && !instance.Shared,
            PreflightFailure.InstanceMismatch);
        PreflightPolicy.Need(instance.Endpoint is not null && instance.Endpoint.Pipe == scope.Endpoint.Pipe
            && instance.Endpoint.InternalServer == scope.Endpoint.InternalServer, PreflightFailure.EndpointMismatch);
    }

    private static PreflightResult Failed(PreflightFailure failure) =>
        new(false, failure, "Offline simulation rejected: " + failure + ".", true);
}
