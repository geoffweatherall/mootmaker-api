# SCP-BLOCKED as of 2026-08-15: `kms` isn't on this account's Service Control Policy allow-list
# (mootmaker-bootstrap-aws-accounts/management-account/scp-guardrails.yaml), so `terraform apply`
# will fail on this resource with an explicit deny until that allow-list is updated - a change
# only Geoff can make (it needs the organization management account, which Claude has no
# credentials for). Written and `terraform validate`-checked, deliberately left unapplied - see
# testing-strategy.md's "Email verification code bypass" section.
#
# Backs CustomEmailSenderBypassHandler (see cognito.tf/lambda.tf): Cognito's CustomEmailSender
# trigger requires a customer-managed KMS key it can encrypt the verification code to before
# handing it to the Lambda - there's no way to use an AWS-managed key here, since the key policy
# below has to explicitly grant the Cognito service principal permission to encrypt with it.
resource "aws_kms_key" "test_email_bypass" {
  count = local.is_ephemeral ? 1 : 0

  description             = "Encrypts verification codes Cognito hands to CustomEmailSenderBypassHandler in ${var.environment}"
  deletion_window_in_days = 7

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountRootFullAccess"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        # No aws:SourceArn condition scoping this to aws_cognito_user_pool.this specifically:
        # that would create a cycle (this key's policy depending on the user pool, whose own
        # lambda_config depends on the Lambda that depends on this key - the same class of
        # circular dependency lambda.tf's admin_gated_env_vars comment already documents working
        # around for post_confirmation_create_person). Scoping to the cognito-idp service
        # principal on this one per-environment, per-purpose key is standard practice for this
        # trigger type - AWS's own simplest CustomEmailSender examples do the same.
        Sid       = "AllowCognitoToEncryptVerificationCodes"
        Effect    = "Allow"
        Principal = { Service = "cognito-idp.amazonaws.com" }
        Action    = "kms:Encrypt"
        Resource  = "*"
      }
    ]
  })
}

resource "aws_kms_alias" "test_email_bypass" {
  count = local.is_ephemeral ? 1 : 0

  name          = "alias/${local.resource_prefix}-test-email-bypass"
  target_key_id = aws_kms_key.test_email_bypass[0].key_id
}
