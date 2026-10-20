param(
    [string]$CommitMessage
)

$ErrorActionPreference = "Stop"
$env:GIT_TERMINAL_PROMPT = "0"
Set-Location $PSScriptRoot

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

public static class AiCallGuardCredentialReader
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CREDENTIAL
    {
        public int Flags;
        public int Type;
        public string TargetName;
        public string Comment;
        public FILETIME LastWritten;
        public int CredentialBlobSize;
        public IntPtr CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias;
        public string UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, int type, int flags, out IntPtr credential);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern void CredFree(IntPtr buffer);

    public static string ReadPassword(string target)
    {
        IntPtr pointer;
        if (!CredRead(target, 1, 0, out pointer))
        {
            throw new InvalidOperationException(
                "Credential not found: " + target + " (error " + Marshal.GetLastWin32Error() + ")");
        }
        try
        {
            CREDENTIAL credential = Marshal.PtrToStructure<CREDENTIAL>(pointer);
            if (credential.CredentialBlobSize <= 0)
            {
                return "";
            }
            return Marshal.PtrToStringUni(
                credential.CredentialBlob,
                credential.CredentialBlobSize / 2);
        }
        finally
        {
            CredFree(pointer);
        }
    }
}
"@

function Get-Token([string]$target) {
    $token = [AiCallGuardCredentialReader]::ReadPassword($target)
    if (-not $token) {
        throw "Credential '$target' is empty. Recreate it with cmdkey."
    }
    return $token
}

function Get-BasicHeader([string]$username, [string]$token) {
    $pair = "${username}:${token}"
    $bytes = [Text.Encoding]::ASCII.GetBytes($pair)
    return "AUTHORIZATION: Basic " + [Convert]::ToBase64String($bytes)
}

$githubToken = Get-Token "AiCallGuard/GitHub"
$giteeToken = Get-Token "AiCallGuard/Gitee"

$changes = git status --porcelain
if ($changes) {
    if (-not $CommitMessage) {
        throw "Working tree has changes. Re-run with -CommitMessage 'your message'."
    }
    git add -A
    git commit -m $CommitMessage
}

$branch = git rev-parse --abbrev-ref HEAD
if (-not $branch) {
    throw "Unable to resolve the current Git branch."
}

$githubHeader = Get-BasicHeader "x-access-token" $githubToken
git -c "http.extraheader=$githubHeader" push origin $branch

$giteeHeader = Get-BasicHeader "cih" $giteeToken
git -c "http.extraheader=$giteeHeader" push gitee $branch

Write-Host "Published branch '$branch' to GitHub and Gitee."
Write-Host "Commit: $(git rev-parse --short HEAD)"
