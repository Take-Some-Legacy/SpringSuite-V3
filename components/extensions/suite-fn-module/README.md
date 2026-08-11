# SpringSuite FN Operator Module

External runtime module for SpringSuite / NorthStar-Suite-V3.

## Purpose

`suite-fn-module` defines twelve explicit operator FN buttons through `suite-fn.yml`.

Default binding:

```text
FN-12 -> desktop.screenshot.send -> active-chat
```

The module does not perform background capture. It only declares and triggers explicit operator routes.

## Commands

```text
fn list
fn show FN-12
fn trigger FN-12
```

## Config

Runtime config is generated as:

```text
config/suite-fn.yml
```

## Deploy target

Signed module jar deploys to:

```text
C:\Users\Aiden\Documents\Take Some\NorthStar-Suite-V3\modules
```
