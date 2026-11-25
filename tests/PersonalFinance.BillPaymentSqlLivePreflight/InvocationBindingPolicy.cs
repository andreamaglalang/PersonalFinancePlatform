using System.Collections.Immutable;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class InvocationBindingPolicy
{
    internal static string OperationName(BindingOperation operation) => operation switch
    {
        BindingOperation.DiscoveryOnlyReview => "discovery-only-review",
        BindingOperation.EndpointBoundMetadataReview => "endpoint-bound-metadata-review",
        _ => throw new BindingException(BindingFailure.InvalidScope)
    };
    // The scope contains the reviewed prefix. Only the exact scope digest suffix is
    // added in the descriptor, avoiding any document referring to its own hash.
    internal static ImmutableArray<string> ScopeArguments(ReviewScopeV2 s) =>
        [s.Command.EntryPoint, "--operation", OperationName(s.Operation), "--request-id", s.RequestId,
            "--run-id", s.RunId, "--invocation-id", s.InvocationId, "--nonce", s.Nonce];
    internal static ImmutableArray<string> FinalArguments(ReviewScopeV2 s, BindingByteIdentity scope) =>
        s.Command.Arguments.AddRange(ImmutableArray.Create("--scope-sha256", scope.Sha256));

    internal static void Descriptor(InvocationDescriptorV1 d)
    {
        BindingPolicyV2.Need(d is not null && d.SchemaVersion == 1 && d.PolicyProfile == BindingPolicyV2.Profile,
            BindingFailure.UnsupportedVersion);
        BindingPolicyV2.Need(BindingPolicyV2.GuidId(d.InvocationId) && BindingPolicyV2.GuidId(d.RequestId)
            && BindingPolicyV2.GuidId(d.RunId) && BindingPolicyV2.GuidId(d.Nonce)
            && Enum.IsDefined(d.Operation) && BindingPolicyV2.ByteIdentity(d.ScopeIdentity)
            && BindingPolicyV2.Hash(d.ManifestSha256) && BindingPolicyV2.Hash(d.RuntimeProfileSha256)
            && BindingPolicyV2.GuidId(d.ExternalReviewReferenceId), BindingFailure.InvalidInvocation);
        BindingPolicyV2.CommandShape(d.Command);
        BindingPolicyV2.WindowShape(d.Window);
    }
    internal static void Bind(ReviewScopeV2 s, BindingByteIdentity scopeIdentity,
        InvocationDescriptorV1 d, BindingObservedLaunch launch)
    {
        BindingPolicyV2.Need(d.RequestId == s.RequestId && d.RunId == s.RunId
            && d.InvocationId == s.InvocationId && d.Nonce == s.Nonce && d.Operation == s.Operation
            && d.ManifestSha256 == s.ManifestSha256 && d.RuntimeProfileSha256 == s.Runtime.Sha256
            && d.Window == s.Window && d.ExternalReviewReferenceId == s.HumanReview.ReferenceId,
            BindingFailure.InvalidInvocation);
        BindingPolicyV2.Need(d.Command.HostPath == s.Command.HostPath
            && d.Command.EntryPoint == s.Command.EntryPoint && d.Command.WorkingDirectory == s.Command.WorkingDirectory
            && d.Command.Arguments.SequenceEqual(FinalArguments(s, scopeIdentity), StringComparer.Ordinal),
            BindingFailure.InvalidInvocation);
        BindingPolicyV2.Need(launch is not null && launch.Complete && launch.HostPath == d.Command.HostPath
            && launch.EntryPoint == d.Command.EntryPoint && launch.WorkingDirectory == d.Command.WorkingDirectory
            && launch.Nonce == d.Nonce && !launch.Arguments.IsDefault
            && launch.Arguments.SequenceEqual(d.Command.Arguments, StringComparer.Ordinal), BindingFailure.ObservationMismatch);
    }
}
