'use strict';
// Credentials are supplied by the operator/CI, never defaulted to a real account password.
module.exports = function requiredSecret(name) {
  const value = process.env[name];
  if (!value) throw new Error(name + ' is required');
  return value;
};
