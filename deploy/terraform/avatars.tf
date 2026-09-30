# Person avatar storage and hosting - see
# mootmaker/designs/archive/person-avatar-upload-refactor.md.
#
# Deliberately one file rather than the usual split across s3.tf/cloudfront.tf/
# domain.tf: these resources are one feature, only exist for each other, and
# this project has no other bucket or distribution to share those files with.
# Splitting them would scatter a single concern across three files where every
# file held nothing else.
#
# This is mootmaker-api's SECOND custom domain, alongside api.<env> in
# domain.tf, and it is owned here on purpose. Serving avatars from
# mootmaker-webapp's distribution would make the two repos mutually dependent
# and - worse - would mean this API could not be deployed and verified on its
# own, which is exactly what verify/'s acceptance suite does.

locals {
  # Same rule as api_domain in domain.tf: production drops the environment
  # segment, everything else keeps it.
  avatars_domain = var.environment == "production" ? "avatars.mootmaker.com" : "avatars.${var.environment}.mootmaker.com"

  # What the resolvers prepend to a stored `v1/<personId>/<sha256>` to build
  # Person.avatarUrl. Configuration, never reconstructed in Java from the
  # environment name - doing that would put this naming rule in two places, and
  # would get production wrong on the first try.
  avatars_base_url = "https://${local.avatars_domain}"

  # Served objects live under avatars/, staged uploads under uploads/. The
  # distribution's origin_path pins it to the former, so `uploads/` is not
  # reachable through CloudFront at all - see the origin block below.
  avatars_served_prefix = "avatars"
  avatars_upload_prefix = "uploads"
}

resource "aws_s3_bucket" "avatars" {
  bucket = "${local.resource_prefix}-avatars-${data.aws_caller_identity.current.account_id}"
  # Ephemeral environments are torn down several times a day and the contents are
  # demo images plus in-flight uploads - nothing worth blocking a destroy over.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "avatars" {
  bucket = aws_s3_bucket.avatars.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Nothing accumulates without a bound. A client that requests an upload URL and
# never confirms leaves an object under uploads/ that no Person references and
# nothing will ever read; this is what deletes it. Served avatars under
# avatars/ are NOT expired - they are referenced by a Person and are removed
# when that person's avatar is replaced or deleted.
resource "aws_s3_bucket_lifecycle_configuration" "avatars" {
  bucket = aws_s3_bucket.avatars.id

  rule {
    id     = "expire-abandoned-uploads"
    status = "Enabled"

    filter {
      prefix = "${local.avatars_upload_prefix}/"
    }

    expiration {
      days = 1
    }

    # A multipart upload that never completes is invisible to the expiration
    # rule above and bills storage indefinitely. Avatars are far too small to
    # be uploaded this way, but the rule costs nothing and the failure mode is
    # silent.
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_cloudfront_origin_access_control" "avatars" {
  name                              = "${local.resource_prefix}-avatars-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "avatars" {
  enabled         = true
  is_ipv6_enabled = true
  price_class     = "PriceClass_100"
  aliases         = [local.avatars_domain]
  comment         = "${local.resource_prefix} person avatars"

  # No default_root_object, and deliberately no custom_error_response blocks.
  #
  # Nothing legitimate ever requests this host's root or a key that does not
  # exist - every real URL is /v1/<personId>/<sha256>.jpg, generated and stored
  # by this API. So a request that misses is a bug or a probe, and both are
  # better served by S3's own 403 than by something friendlier.
  #
  # Stating that explicitly matters. mootmaker-webapp's distribution rewrites
  # 403/404 to index.html at status 200 because it is a SPA, and an avatar
  # served through it therefore failed silently - a missing image returned HTML
  # that the browser could not decode, and the initials fallback hid it. That
  # bug is the reason this design exists; inheriting the same unexamined
  # default here would reintroduce it.

  origin {
    domain_name              = aws_s3_bucket.avatars.bucket_regional_domain_name
    origin_id                = "s3-avatars"
    origin_access_control_id = aws_cloudfront_origin_access_control.avatars.id

    # Pins the distribution to the served prefix, so the public URL carries no
    # prefix of its own (avatars.mootmaker.com/v1/... rather than a stuttering
    # avatars.mootmaker.com/avatars/v1/...) and, more importantly, so the
    # uploads/ staging prefix cannot be fetched through CloudFront even with an
    # exact key. That is a stronger guarantee than a bucket policy, because it
    # does not depend on reading the policy correctly.
    origin_path = "/${local.avatars_served_prefix}"
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-avatars"
    viewer_protocol_policy = "redirect-to-https"

    # AWS managed CachingOptimized. Its default TTL applies only when the
    # origin sends no caching headers, and ours always does: ConfirmAvatarUpload
    # writes Cache-Control: public, max-age=31536000, immutable on every object.
    # That is safe because keys are content-addressed - different bytes mean a
    # different key - so a cached URL can never go stale, and replacing an
    # avatar needs no invalidation.
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.avatars.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# Scoped to this exact distribution, which is possible precisely because the
# same repository owns both. The shared-distribution design this replaced would
# have needed an account-scoped wildcard here, since naming mootmaker-webapp's
# distribution would have meant depending on its Terraform state.
data "aws_iam_policy_document" "avatars_bucket_policy" {
  statement {
    sid       = "AllowCloudFrontRead"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.avatars.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.avatars.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "avatars" {
  bucket = aws_s3_bucket.avatars.id
  policy = data.aws_iam_policy_document.avatars_bucket_policy.json
}

# Certificate and DNS, following domain.tf's pattern exactly - see its header
# comment for why each environment provisions its own certificate rather than
# sharing a wildcard. data.aws_route53_zone.this is declared there.
resource "aws_acm_certificate" "avatars" {
  provider          = aws.us_east_1
  domain_name       = local.avatars_domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "avatars_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.avatars.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id = data.aws_route53_zone.this.zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60
}

resource "aws_acm_certificate_validation" "avatars" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.avatars.arn
  validation_record_fqdns = [for record in aws_route53_record.avatars_cert_validation : record.fqdn]
}

resource "aws_route53_record" "avatars_alias" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.avatars_domain
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.avatars.domain_name
    zone_id                = aws_cloudfront_distribution.avatars.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "avatars_alias_ipv6" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.avatars_domain
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.avatars.domain_name
    zone_id                = aws_cloudfront_distribution.avatars.hosted_zone_id
    evaluate_target_health = false
  }
}
