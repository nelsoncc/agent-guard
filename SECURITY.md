# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |

## Reporting a vulnerability

If you discover a security vulnerability in Agent Guard, please report it responsibly.

**Do not open a public GitHub issue.**

Instead, send an email to **security@agentguard.io** with:

- a description of the vulnerability
- steps to reproduce
- potential impact
- any suggested fix (optional)

You should receive an acknowledgment within 48 hours. We will work with you to understand the issue and coordinate a fix
before any public disclosure.

## Scope

Agent Guard is a runtime governance SDK. Security issues we care about include:

- bypasses of budget enforcement, tool policy, or injection scanning
- policy-as-code parsing vulnerabilities
- thread-safety issues that could allow unauthorized tool execution
- dependency vulnerabilities in shipped modules

## Responsible disclosure

We follow coordinated disclosure. We ask that you give us reasonable time to address the issue before making it public.

We credit all reporters in the changelog unless you prefer to remain anonymous.
