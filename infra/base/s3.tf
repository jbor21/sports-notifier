# Where `make aws-down` puts the Postgres dump and the next first boot reads it (spec section 5).
# It holds password hashes: private, encrypted, never public.
resource "aws_s3_bucket" "dumps" {
  bucket = "sports-notifier-dumps-${local.account_id}"
}

resource "aws_s3_bucket_public_access_block" "dumps" {
  bucket                  = aws_s3_bucket.dumps.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dumps" {
  bucket = aws_s3_bucket.dumps.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# dumps/latest.dump is overwritten by every teardown; versioning keeps the earlier ones.
resource "aws_s3_bucket_versioning" "dumps" {
  bucket = aws_s3_bucket.dumps.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "dumps" {
  bucket = aws_s3_bucket.dumps.id
  rule {
    id     = "expire-old-dumps"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
  depends_on = [aws_s3_bucket_versioning.dumps]
}
